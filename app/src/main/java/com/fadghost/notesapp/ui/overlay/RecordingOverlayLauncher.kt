package com.fadghost.notesapp.ui.overlay

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import com.fadghost.notesapp.data.audio.VoiceRecordingSession
import com.fadghost.notesapp.data.audio.VoiceSessionState

/** Permission-gated entry point for the recording bubble shown over other apps. */
object RecordingOverlayLauncher {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** Returns false when permission is absent; callers retain the in-app/notification UI. */
    fun show(context: Context): Boolean {
        if (!canDraw(context)) return false
        val active = VoiceRecordingSession.state.value
        val sessionId = when (active) {
            is VoiceSessionState.Starting -> active.sessionId
            is VoiceSessionState.Recording -> active.sessionId
            else -> return false
        }
        // The session-scoped command makes the existing microphone FGS bind this overlay
        // before/while SHOW is delivered. This also fixes the common path where permission
        // is granted after recording already started and the initial bind was unavailable.
        val keepaliveRequested = runCatching {
            VoiceRecordingSession.ensureOverlayKeepalive(context, sessionId)
        }.isSuccess
        if (!keepaliveRequested) return false
        return runCatching {
            context.startService(
                Intent(context, RecordingOverlayService::class.java)
                    .setAction(RecordingOverlayService.ACTION_SHOW)
            )
            true
        }.getOrDefault(false)
    }

    fun hide(context: Context) {
        // Terminal VoiceSessionState removes the window; this only clears any started
        // service state and never attempts a prohibited background service launch.
        runCatching { context.stopService(Intent(context, RecordingOverlayService::class.java)) }
    }

    /**
     * Bind the overlay component to the already-running microphone foreground service.
     * The foreground service owns this connection for exactly the active session, which
     * keeps Android from treating the overlay as an unrelated disposable background
     * service. This does not create another foreground notification.
     */
    fun bindKeepalive(context: Context): ServiceConnection? {
        if (!canDraw(context)) return null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(
                Intent(context, RecordingOverlayService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrDefault(false)
        return connection.takeIf { bound }
    }

    fun unbindKeepalive(context: Context, connection: ServiceConnection?) {
        if (connection == null) return
        runCatching { context.unbindService(connection) }
    }
}
