package com.fadghost.notesapp.data.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Binder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fadghost.notesapp.MainActivity
import com.fadghost.notesapp.R
import com.fadghost.notesapp.ui.overlay.RecordingOverlayLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Process-wide recording state. The foreground service is the only [AudioRecorder] owner. */
sealed interface VoiceSessionState {
    val sessionId: String?
    val targetNoteId: Long?
    val transcriptOnly: Boolean
    data object Idle : VoiceSessionState {
        override val sessionId: String? = null
        override val targetNoteId: Long? = null
        override val transcriptOnly: Boolean = false
    }
    data class Starting(
        override val sessionId: String,
        override val targetNoteId: Long,
        override val transcriptOnly: Boolean
    ) : VoiceSessionState
    data class Recording(
        override val sessionId: String,
        override val targetNoteId: Long,
        override val transcriptOnly: Boolean,
        val paused: Boolean,
        val elapsedMs: Long,
        val amplitudes: List<Float>
    ) : VoiceSessionState
    data class Finished(
        override val sessionId: String,
        override val targetNoteId: Long,
        override val transcriptOnly: Boolean,
        val segments: List<RecordedSegment>
    ) : VoiceSessionState
    data class Failed(
        override val sessionId: String,
        override val targetNoteId: Long,
        override val transcriptOnly: Boolean,
        val message: String
    ) : VoiceSessionState
}

/** Discard always wins when stop/discard race during recorder startup. */
object VoiceTerminalPolicy {
    fun merge(existingDiscard: Boolean?, incomingDiscard: Boolean): Boolean =
        existingDiscard == true || incomingDiscard
}

/**
 * Command seam shared by the editor sheet and the system overlay. Commands are idempotent and
 * session-scoped, so a stale overlay cannot stop a newer recording.
 */
object VoiceRecordingSession {
    private val _state = MutableStateFlow<VoiceSessionState>(VoiceSessionState.Idle)
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var terminalClaim: Pair<String, String>? = null

    @Synchronized
    internal fun publish(value: VoiceSessionState) {
        if (value !is VoiceSessionState.Finished) terminalClaim = null
        _state.value = value
    }

    @Synchronized
    fun claimFinished(sessionId: String, consumerId: String): VoiceSessionState.Finished? {
        val finished = _state.value as? VoiceSessionState.Finished ?: return null
        if (finished.sessionId != sessionId) return null
        val existing = terminalClaim
        if (existing != null && existing != (sessionId to consumerId)) return null
        terminalClaim = sessionId to consumerId
        return finished
    }

    @Synchronized
    fun acknowledge(sessionId: String, consumerId: String) {
        if (terminalClaim == sessionId to consumerId && _state.value.sessionId == sessionId) {
            terminalClaim = null
            _state.value = VoiceSessionState.Idle
        }
    }

    @Synchronized
    fun releaseClaim(sessionId: String, consumerId: String) {
        if (terminalClaim == sessionId to consumerId) terminalClaim = null
    }

    @Synchronized
    fun clearTerminal(sessionId: String) {
        if (_state.value.sessionId == sessionId &&
            (_state.value is VoiceSessionState.Failed || _state.value is VoiceSessionState.Finished)
        ) {
            terminalClaim = null
            _state.value = VoiceSessionState.Idle
        }
    }

    fun start(
        context: Context,
        sessionId: String,
        noteDir: java.io.File,
        targetNoteId: Long,
        transcriptOnly: Boolean
    ) = send(context, VoiceRecordingService.ACTION_START, sessionId, noteDir.absolutePath, targetNoteId, transcriptOnly)
    fun togglePause(context: Context, sessionId: String) = send(context, VoiceRecordingService.ACTION_TOGGLE_PAUSE, sessionId)
    fun stop(context: Context, sessionId: String) = send(context, VoiceRecordingService.ACTION_STOP, sessionId)
    fun discard(context: Context, sessionId: String) = send(context, VoiceRecordingService.ACTION_DISCARD, sessionId)
    fun ensureOverlayKeepalive(context: Context, sessionId: String) =
        send(context, VoiceRecordingService.ACTION_BIND_OVERLAY, sessionId)

    private fun send(
        context: Context,
        action: String,
        sessionId: String,
        noteDir: String? = null,
        targetNoteId: Long = -1,
        transcriptOnly: Boolean = false
    ) {
        val intent = Intent(context, VoiceRecordingService::class.java)
            .setAction(action)
            .putExtra(VoiceRecordingService.EXTRA_SESSION_ID, sessionId)
        if (noteDir != null) intent.putExtra(VoiceRecordingService.EXTRA_NOTE_DIR, noteDir)
        intent.putExtra(VoiceRecordingService.EXTRA_TARGET_NOTE_ID, targetNoteId)
        intent.putExtra(VoiceRecordingService.EXTRA_TRANSCRIPT_ONLY, transcriptOnly)
        try {
            if (action == VoiceRecordingService.ACTION_START) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } catch (error: Exception) {
            if (error is SecurityException || error is ForegroundServiceStartNotAllowedException) {
                publish(VoiceSessionState.Failed(sessionId, targetNoteId, transcriptOnly, "Recording could not start in the background."))
            } else throw error
        }
    }
}

/** Foreground microphone owner used by both in-app and overlay controls. */
class VoiceRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var recorder: AudioRecorder? = null
    private var activeId: String? = null
    private var targetNoteId: Long = -1
    private var transcriptOnly: Boolean = false
    private var ticker: Job? = null
    @Volatile private var pendingFinish: Pair<String, Boolean>? = null
    private val binder = Binder()
    private var overlayConnection: ServiceConnection? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> startSession(
                id,
                intent.getStringExtra(EXTRA_NOTE_DIR),
                intent.getLongExtra(EXTRA_TARGET_NOTE_ID, -1),
                intent.getBooleanExtra(EXTRA_TRANSCRIPT_ONLY, false)
            )
            ACTION_TOGGLE_PAUSE -> ifCurrent(id) { togglePause(id) }
            ACTION_STOP -> requestFinish(id, discard = false)
            ACTION_DISCARD -> requestFinish(id, discard = true)
            ACTION_BIND_OVERLAY -> ifCurrent(id) { ensureOverlayKeepalive() }
        }
        return START_NOT_STICKY
    }

    private fun startSession(id: String, dir: String?, targetNoteId: Long, transcriptOnly: Boolean) {
        if (activeId == id) return
        if (dir.isNullOrBlank()) {
            VoiceRecordingSession.publish(VoiceSessionState.Failed(id, targetNoteId, transcriptOnly, "Recording folder is unavailable."))
            stopSelf()
            return
        }
        ensureChannel()
        activeId = id
        this.targetNoteId = targetNoteId
        this.transcriptOnly = transcriptOnly
        pendingFinish = null
        VoiceRecordingSession.publish(VoiceSessionState.Starting(id, targetNoteId, transcriptOnly))
        try {
            startForeground(NOTIFICATION_ID, notification("Recording voice note"))
        } catch (error: Exception) {
            if (error is SecurityException || error is ForegroundServiceStartNotAllowedException) {
                activeId = null
                VoiceRecordingSession.publish(
                    VoiceSessionState.Failed(id, targetNoteId, transcriptOnly, "Recording could not start in the background.")
                )
                stopSelf()
                return
            }
            throw error
        }
        ensureOverlayKeepalive()
        scope.launch {
            lock.withLock {
                recorder?.let { runCatching { withContext(Dispatchers.IO) { it.discard() } } }
                val next = AudioRecorder(applicationContext, java.io.File(dir))
                val started = runCatching { withContext(Dispatchers.IO) { next.start() } }.isSuccess
                if (!started) {
                    withContext(Dispatchers.IO) { runCatching { next.discard() } }
                    VoiceRecordingSession.publish(VoiceSessionState.Failed(id, targetNoteId, transcriptOnly, "Couldn't start recording."))
                    stopForeground(STOP_FOREGROUND_REMOVE); releaseOverlayKeepalive(); stopSelf(); return@withLock
                }
                recorder = next
                pendingFinish?.takeIf { it.first == id }?.let { (_, discard) ->
                    pendingFinish = null
                    finishLocked(id, discard)
                    return@withLock
                }
                VoiceRecordingSession.publish(VoiceSessionState.Recording(id, targetNoteId, transcriptOnly, false, 0, emptyList()))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Recording voice note"))
                startTicker(id)
            }
        }
    }

    private fun requestFinish(id: String, discard: Boolean) {
        if (activeId != id) return
        val old = pendingFinish
        pendingFinish = id to VoiceTerminalPolicy.merge(old?.second, discard)
        scope.launch {
            lock.withLock {
                if (activeId == id && recorder != null) {
                    val command = pendingFinish
                    pendingFinish = null
                    finishLocked(id, command?.second ?: discard)
                }
            }
        }
    }

    private fun togglePause(id: String) = scope.launch {
        lock.withLock {
            val rec = recorder ?: return@withLock
            withContext(Dispatchers.IO) { if (rec.isPaused) rec.resume() else rec.pause() }
            val old = VoiceRecordingSession.state.value as? VoiceSessionState.Recording
            VoiceRecordingSession.publish(
                VoiceSessionState.Recording(id, targetNoteId, transcriptOnly, rec.isPaused, rec.totalElapsedMs(), old?.amplitudes.orEmpty())
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification(if (rec.isPaused) "Voice note paused" else "Recording voice note"))
        }
    }

    private suspend fun finishLocked(id: String, discard: Boolean) {
        ticker?.cancel(); ticker = null
        val rec = recorder
        recorder = null
        activeId = null
        if (discard) {
            withContext(Dispatchers.IO) { runCatching { rec?.discard() } }
            VoiceRecordingSession.publish(VoiceSessionState.Idle)
        } else {
            val segments = withContext(Dispatchers.IO) {
                runCatching { rec?.stop().orEmpty() }.getOrDefault(emptyList())
            }
            VoiceRecordingSession.publish(
                if (segments.isEmpty()) VoiceSessionState.Failed(id, targetNoteId, transcriptOnly, "Nothing was recorded.")
                else VoiceSessionState.Finished(id, targetNoteId, transcriptOnly, segments)
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseOverlayKeepalive()
        stopSelf()
    }

    private fun releaseOverlayKeepalive() {
        overlayConnection?.let { RecordingOverlayLauncher.unbindKeepalive(this, it) }
        overlayConnection = null
    }

    private fun ensureOverlayKeepalive() {
        if (overlayConnection == null) overlayConnection = RecordingOverlayLauncher.bindKeepalive(this)
    }

    private fun startTicker(id: String) {
        ticker?.cancel()
        ticker = scope.launch {
            while (activeId == id) {
                val rec = recorder ?: break
                val sample = lock.withLock {
                    withContext(Dispatchers.IO) { rec.maybeRollover() }
                    val raw = rec.amplitude().toFloat() / MAX_AMPLITUDE
                    sqrt(raw.coerceIn(0f, 1f))
                }
                val old = VoiceRecordingSession.state.value as? VoiceSessionState.Recording
                val amplitudes = (old?.amplitudes.orEmpty() + sample).takeLast(MAX_SAMPLES)
                VoiceRecordingSession.publish(VoiceSessionState.Recording(id, targetNoteId, transcriptOnly, rec.isPaused, rec.totalElapsedMs(), amplitudes))
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private inline fun ifCurrent(id: String, block: () -> Unit) { if (activeId == id) block() }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val id = activeId
        val paused = recorder?.isPaused == true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Notesapp")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (id != null) {
            builder.addAction(
                0,
                if (paused) "Resume" else "Pause",
                serviceAction(ACTION_TOGGLE_PAUSE, id, 1)
            )
            builder.addAction(0, "Stop", serviceAction(ACTION_STOP, id, 2))
        }
        return builder.build()
    }

    private fun serviceAction(action: String, id: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VoiceRecordingService::class.java).setAction(action).putExtra(EXTRA_SESSION_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        ticker?.cancel()
        val orphanedId = activeId
        val orphanedRecorder = recorder
        activeId = null
        recorder = null
        if (orphanedId != null) {
            if (orphanedRecorder != null) runCatching { orphanedRecorder.discard() }
            VoiceRecordingSession.publish(
                VoiceSessionState.Failed(orphanedId, targetNoteId, transcriptOnly, "Recording was interrupted.")
            )
        }
        releaseOverlayKeepalive()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.fadghost.notesapp.voice.START"
        const val ACTION_TOGGLE_PAUSE = "com.fadghost.notesapp.voice.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.fadghost.notesapp.voice.STOP"
        const val ACTION_DISCARD = "com.fadghost.notesapp.voice.DISCARD"
        const val ACTION_BIND_OVERLAY = "com.fadghost.notesapp.voice.BIND_OVERLAY"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_NOTE_DIR = "note_dir"
        const val EXTRA_TARGET_NOTE_ID = "target_note_id"
        const val EXTRA_TRANSCRIPT_ONLY = "transcript_only"
        private const val CHANNEL_ID = "voice_recording"
        private const val NOTIFICATION_ID = 4401
        private const val SAMPLE_INTERVAL_MS = 80L
        private const val MAX_SAMPLES = 96
        private const val MAX_AMPLITUDE = 32767f
    }
}
