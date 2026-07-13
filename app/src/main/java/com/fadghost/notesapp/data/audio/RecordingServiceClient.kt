package com.fadghost.notesapp.data.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fadghost.notesapp.service.RecordingService

/** Intent-only façade used by Compose/view-model code and by notification actions. */
object RecordingServiceClient {
    fun start(context: Context, sessionId: String) {
        ContextCompat.startForegroundService(context, intent(context, RecordingService.ACTION_START, sessionId))
    }

    fun pause(context: Context, sessionId: String) = send(context, RecordingService.ACTION_PAUSE, sessionId)
    fun resume(context: Context, sessionId: String) = send(context, RecordingService.ACTION_RESUME, sessionId)
    fun stop(context: Context, sessionId: String) = send(context, RecordingService.ACTION_STOP, sessionId)
    fun discard(context: Context, sessionId: String) = send(context, RecordingService.ACTION_DISCARD, sessionId)
    fun showRambleOverlay(context: Context, sessionId: String) =
        send(context, RecordingService.ACTION_SHOW_RAMBLE_OVERLAY, sessionId)

    private fun send(context: Context, action: String, sessionId: String) {
        // These commands target an already-running service. Notification PendingIntents also
        // use the same explicit intents, which are allowed from the background.
        context.startService(intent(context, action, sessionId))
    }

    fun intent(context: Context, action: String, sessionId: String): Intent =
        Intent(context, RecordingService::class.java).apply {
            this.action = action
            putExtra(RecordingService.EXTRA_SESSION_ID, sessionId)
        }
}
