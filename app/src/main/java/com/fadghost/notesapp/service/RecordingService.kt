package com.fadghost.notesapp.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fadghost.notesapp.MainActivity
import com.fadghost.notesapp.data.ai.work.RamblePipelineWorker
import com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker
import com.fadghost.notesapp.data.audio.AudioRecorder
import com.fadghost.notesapp.data.audio.AudioStorage
import com.fadghost.notesapp.data.audio.RecordedSegment
import com.fadghost.notesapp.data.audio.RecordingServiceClient
import com.fadghost.notesapp.data.audio.VoiceDestination
import com.fadghost.notesapp.data.audio.VoiceRuntimeState
import com.fadghost.notesapp.data.audio.VoiceSession
import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.data.audio.VoiceSessionStore
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * The sole owner of MediaRecorder for a durable capture. A view-model only sends commands and
 * observes [VoiceSessionStore], so leaving/recreating the Activity cannot tear down the recorder.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var sessions: VoiceSessionStore
    @Inject lateinit var notes: NotesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorderLock = Mutex()
    private var active: ActiveRecording? = null
    private var lastNotificationSecond = -1L

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf { VoiceSession.SAFE_ID.matches(it) }
            ?: return START_NOT_STICKY

        if (action == ACTION_START) {
            // Promotion must happen immediately, before recorder preparation or disk reads.
            if (!promote(sessionId)) return START_NOT_STICKY
        }

        scope.launch {
            when (action) {
                ACTION_START -> startCapture(sessionId)
                ACTION_PAUSE -> pauseCapture(sessionId)
                ACTION_RESUME -> resumeCapture(sessionId)
                ACTION_STOP -> stopCapture(sessionId, enqueue = true)
                ACTION_DISCARD -> discardCapture(sessionId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(sessionId: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch { failSession(sessionId, "permission", "Microphone permission is required.") }
            stopSelf()
            return false
        }
        return runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(sessionId, paused = false, elapsedMs = 0L),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }.onFailure {
            scope.launch { failSession(sessionId, "foreground_start", "Couldn't start background recording.") }
            stopSelf()
        }.isSuccess
    }

    private suspend fun startCapture(sessionId: String) {
        recorderLock.withLock {
            active?.let { current ->
                // Duplicate START for the active session is idempotent. A different id is stale
                // or concurrent and must never steal the microphone.
                if (current.session.id == sessionId) {
                    notifyState(current, force = true)
                    return
                }
                failSession(sessionId, "busy", "Another recording is already active.")
                notifyState(current, force = true)
                return
            }
            val session = sessions.get(sessionId)
            if (session == null || session.phase !in setOf(VoiceSessionPhase.PREPARING, VoiceSessionPhase.INTERRUPTED)) {
                failSession(sessionId, "invalid_session", "That recording session cannot be started.")
                finishForeground(sessionId)
                return
            }
            val directory = audioDirectory(session)
            val recorder = AudioRecorder(this, directory)
            val started = withContext(Dispatchers.IO) { runCatching { recorder.start() }.isSuccess }
            if (!started) {
                failSession(sessionId, "recorder_start", "Couldn't start recording.")
                finishForeground(sessionId)
                return
            }
            val updated = sessions.update(sessionId) {
                it.copy(phase = VoiceSessionPhase.RECORDING, errorCode = null, errorMessage = null)
            }
            if (updated == null) {
                withContext(Dispatchers.IO) { recorder.discard() }
                finishForeground(sessionId)
                return
            }
            val live = ActiveRecording(updated, recorder)
            active = live
            publish(live, VoiceSessionPhase.RECORDING)
            live.ticker = startTicker(live)
        }
    }

    private suspend fun pauseCapture(sessionId: String) {
        recorderLock.withLock {
            val live = activeFor(sessionId) ?: return
            val paused = withContext(Dispatchers.IO) { live.recorder.pause() }
            if (!paused) return
            sessions.update(sessionId) { it.copy(phase = VoiceSessionPhase.PAUSED) }
            publish(live, VoiceSessionPhase.PAUSED)
            notifyState(live, force = true)
        }
    }

    private suspend fun resumeCapture(sessionId: String) {
        recorderLock.withLock {
            val live = activeFor(sessionId) ?: return
            val resumed = withContext(Dispatchers.IO) { live.recorder.resume() }
            if (!resumed) return
            sessions.update(sessionId) { it.copy(phase = VoiceSessionPhase.RECORDING) }
            publish(live, VoiceSessionPhase.RECORDING)
            notifyState(live, force = true)
        }
    }

    private suspend fun stopCapture(sessionId: String, enqueue: Boolean) {
        val segments = recorderLock.withLock {
            val live = activeFor(sessionId) ?: return@withLock null
            // Claim the finalisation while holding the same lock used by pause/resume/ticker.
            // A rapid double-tap on Stop can therefore never stop twice and overwrite a
            // successful RECORDED state with an empty-recording error.
            active = null
            live.ticker?.cancel()
            withContext(Dispatchers.IO) { live.recorder.stop() }
        } ?: return
        if (segments.isEmpty()) {
            failSession(sessionId, "empty_recording", "Nothing usable was recorded.")
        } else {
            val updated = sessions.update(sessionId) {
                it.copy(
                    phase = VoiceSessionPhase.RECORDED,
                    segments = segments,
                    errorCode = null,
                    errorMessage = null
                )
            }
            if (enqueue && updated != null) enqueuePipeline(updated, segments)
        }
        finishForeground(sessionId)
    }

    private suspend fun discardCapture(sessionId: String) {
        val discardedActiveCapture = recorderLock.withLock {
            val live = activeFor(sessionId) ?: return@withLock false
            active = null
            live.ticker?.cancel()
            withContext(Dispatchers.IO) { live.recorder.discard() }
            true
        }
        // Discard is a capture control, not a way to erase a completed attachment by sending a
        // stale service Intent. Pending/review sessions remain available through their note.
        if (!discardedActiveCapture) {
            val stale = sessions.get(sessionId)
            val safeToClean = stale != null &&
                stale.phase in setOf(
                    VoiceSessionPhase.PREPARING,
                    VoiceSessionPhase.ERROR,
                    VoiceSessionPhase.INTERRUPTED
                ) &&
                !stale.noteCommitted && !stale.audioCommitted && stale.segments.isEmpty()
            if (safeToClean) {
                sessions.update(sessionId) { it.copy(phase = VoiceSessionPhase.DISCARDED) }
                stale.targetNoteId?.let { runCatching { notes.hardDeleteIfEmpty(it) } }
                runCatching { audioDirectory(stale).deleteRecursively() }
            }
            finishForeground(sessionId)
            return
        }
        val session = sessions.update(sessionId) {
            it.copy(phase = VoiceSessionPhase.DISCARDED, segments = emptyList())
        }
        if (session?.destination == VoiceDestination.RAMBLE_NOTE) {
            session.targetNoteId?.let { runCatching { notes.hardDeleteIfEmpty(it) } }
        }
        if (session != null) {
            runCatching { audioDirectory(session).deleteRecursively() }
        }
        finishForeground(sessionId)
    }

    private fun startTicker(live: ActiveRecording): Job = scope.launch {
        try {
            while (active?.session?.id == live.session.id) {
                val sample = recorderLock.withLock {
                    withContext(Dispatchers.IO) { live.recorder.maybeRollover() }
                    live.recorder.totalElapsedMs() to normalize(live.recorder.amplitude())
                }
                live.amplitudes = (live.amplitudes + sample.second).takeLast(MAX_SAMPLES)
                publish(
                    live,
                    if (live.recorder.isPaused) VoiceSessionPhase.PAUSED else VoiceSessionPhase.RECORDING,
                    sample.first
                )
                notifyState(live)
                delay(SAMPLE_INTERVAL_MS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            recoverAfterRecorderFailure(live)
        }
    }

    /** Preserve already-finalised segments and end the FGS if a rollover/device failure occurs. */
    private suspend fun recoverAfterRecorderFailure(live: ActiveRecording) {
        val segments = recorderLock.withLock {
            if (active?.session?.id != live.session.id) return@withLock null
            active = null
            withContext(Dispatchers.IO) { live.recorder.stop() }
        } ?: return
        try {
            if (segments.isEmpty()) {
                failSession(live.session.id, "recorder_failure", "Recording stopped because the microphone failed.")
            } else {
                val updated = sessions.update(live.session.id) {
                    it.copy(
                        phase = VoiceSessionPhase.RECORDED,
                        segments = segments,
                        warnings = (it.warnings + "Recording ended early, but the captured audio was saved.").distinct(),
                        errorCode = null,
                        errorMessage = null
                    )
                }
                if (updated != null) enqueuePipeline(updated, segments)
            }
        } finally {
            finishForeground(live.session.id)
        }
    }

    private fun publish(
        live: ActiveRecording,
        phase: VoiceSessionPhase,
        elapsedMs: Long = live.recorder.totalElapsedMs()
    ) {
        sessions.publishRuntime(
            VoiceRuntimeState(
                sessionId = live.session.id,
                phase = phase,
                paused = live.recorder.isPaused,
                elapsedMs = elapsedMs,
                amplitudes = live.amplitudes
            )
        )
    }

    private fun notifyState(live: ActiveRecording, force: Boolean = false) {
        val elapsed = live.recorder.totalElapsedMs()
        val second = elapsed / 1_000L
        if (!force && second == lastNotificationSecond) return
        lastNotificationSecond = second
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            notification(live.session.id, live.recorder.isPaused, elapsed)
        )
    }

    private fun enqueuePipeline(session: VoiceSession, segments: List<RecordedSegment>) {
        when (session.destination) {
            VoiceDestination.RAMBLE_NOTE -> RamblePipelineWorker.enqueue(this, session.id)
            VoiceDestination.NOTE_APPEND -> session.targetNoteId?.let {
                TranscribeQueueWorker.enqueue(this, it, segments)
            }
            // Diary insertion owns cursor semantics in UI. Its durable RECORDED state is
            // intentionally left for that host rather than mutating a diary row in background.
            VoiceDestination.DIARY_TRANSCRIPT -> Unit
        }
    }

    private fun audioDirectory(session: VoiceSession): File = when (session.destination) {
        VoiceDestination.RAMBLE_NOTE, VoiceDestination.NOTE_APPEND ->
            AudioStorage.recordingDir(filesDir, requireNotNull(session.targetNoteId), session.id)
        VoiceDestination.DIARY_TRANSCRIPT -> sessions.transientAudioDir(session.id)
    }

    private fun activeFor(sessionId: String): ActiveRecording? =
        active?.takeIf { it.session.id == sessionId }

    private suspend fun failSession(sessionId: String, code: String, message: String) {
        sessions.update(sessionId) {
            it.copy(phase = VoiceSessionPhase.ERROR, errorCode = code, errorMessage = message)
        }
        sessions.publishRuntime(
            VoiceRuntimeState(sessionId, VoiceSessionPhase.ERROR, false, 0L, error = message)
        )
    }

    private fun finishForeground(sessionId: String) {
        sessions.clearRuntime(sessionId)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(sessionId: String, paused: Boolean, elapsedMs: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (paused) "Resume" else "Pause"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Voice recording paused" else "Recording voice")
            .setContentText(formatElapsed(elapsedMs))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, pauseLabel, commandIntent(sessionId, pauseAction, REQUEST_PAUSE))
            .addAction(0, "Stop", commandIntent(sessionId, ACTION_STOP, REQUEST_STOP))
            .build()
    }

    private fun commandIntent(sessionId: String, action: String, lane: Int): PendingIntent =
        PendingIntent.getService(
            this,
            lane,
            RecordingServiceClient.intent(this, action, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for an active voice recording"
                setSound(null, null)
            }
        )
    }

    override fun onDestroy() {
        val interrupted = active
        active = null
        interrupted?.ticker?.cancel()
        if (interrupted != null) {
            // Best effort only: START_NOT_STICKY prevents an unsafe microphone restart. If
            // Android grants onDestroy time, preserve valid finalised segments for recovery.
            runBlocking {
                withTimeoutOrNull(3_000L) {
                    val segments = recorderLock.withLock {
                        withContext(Dispatchers.IO) { interrupted.recorder.stop() }
                    }
                    sessions.update(interrupted.session.id) {
                        if (segments.isEmpty()) it.copy(phase = VoiceSessionPhase.INTERRUPTED)
                        else it.copy(phase = VoiceSessionPhase.RECORDED, segments = segments)
                    }
                    if (segments.isNotEmpty()) enqueuePipeline(interrupted.session, segments)
                }
            }
            sessions.clearRuntime(interrupted.session.id)
        }
        scope.cancel()
        super.onDestroy()
    }

    private data class ActiveRecording(
        val session: VoiceSession,
        val recorder: AudioRecorder,
        var ticker: Job? = null,
        var amplitudes: List<Float> = emptyList()
    )

    companion object {
        const val ACTION_START = "com.fadghost.notesapp.voice.START"
        const val ACTION_PAUSE = "com.fadghost.notesapp.voice.PAUSE"
        const val ACTION_RESUME = "com.fadghost.notesapp.voice.RESUME"
        const val ACTION_STOP = "com.fadghost.notesapp.voice.STOP"
        const val ACTION_DISCARD = "com.fadghost.notesapp.voice.DISCARD"
        const val EXTRA_SESSION_ID = "voice_session_id"

        private const val CHANNEL_ID = "voice_recording"
        private const val NOTIFICATION_ID = 0x4e07
        private const val REQUEST_OPEN = 0x700
        private const val REQUEST_PAUSE = 0x701
        private const val REQUEST_STOP = 0x702
        private const val SAMPLE_INTERVAL_MS = 80L
        private const val MAX_SAMPLES = 96
        private const val MAX_AMPLITUDE = 32767f

        private fun normalize(raw: Int): Float =
            sqrt((raw.toFloat() / MAX_AMPLITUDE).coerceIn(0f, 1f))

        private fun formatElapsed(ms: Long): String {
            val seconds = ms / 1_000L
            return "%d:%02d".format(seconds / 60L, seconds % 60L)
        }
    }
}
