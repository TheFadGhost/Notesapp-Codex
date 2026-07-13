package com.fadghost.notesapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Central, idempotent notification-channel setup (PLAN.md §8). Creating a channel
 * that already exists is a no-op in the framework, so this is safe to call from
 * multiple places and safe if the Diary agent also (re)creates the shared Nudges
 * channel — same id, same importance, no conflict.
 */
object NotificationChannels {
    const val EVENTS = "events"
    const val REMINDERS = "reminders"   // HIGH — reminders that must be noticed
    const val NUDGES = "nudges"         // DEFAULT — journaling nudge etc. (shared w/ diary)
    const val AI_RESULTS = "ai_results" // LOW — background AI finished

    fun ensure(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(EVENTS, "Event alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts before calendar events"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Time-based reminders you set"
                enableVibration(true)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(NUDGES, "Nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Gentle prompts, like the daily journaling nudge"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(AI_RESULTS, "AI results", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background AI clean-ups that finished"
            }
        )
    }
}
