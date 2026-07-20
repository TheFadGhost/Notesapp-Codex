package com.fadghost.notesapp

import android.app.Application
import com.fadghost.notesapp.alarm.EventAlarm
import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.memory.MemoryRepository
import com.fadghost.notesapp.data.webhook.WebhookServerController
import com.fadghost.notesapp.data.work.TrashPurgeWorker
import com.fadghost.notesapp.notify.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NotesApp : Application() {

    @Inject lateinit var reminderAlarm: ReminderAlarm
    @Inject lateinit var eventAlarm: EventAlarm
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var webhookServerController: WebhookServerController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Deferrable guaranteed work: purge 30-day-old trash daily (PLAN.md §7).
        TrashPurgeWorker.schedule(this)
        // Idempotent notification channels (PLAN.md §8) — safe alongside the diary
        // agent creating the shared Nudges channel.
        NotificationChannels.ensure(this)
        // Defensive re-arm of every pending reminder on cold start (audit H1): exact
        // alarms are dropped on process death, so this backstops BootReceiver for
        // alarms armed by paths that don't go through reboot (e.g. AI-extracted
        // reminders). Runs off the main thread — never block onCreate on a DB read.
        appScope.launch { runCatching { reminderAlarm.rescheduleAll() } }
        appScope.launch { runCatching { eventAlarm.rescheduleAll() } }
        // Memory vault (M-B): files are the source of truth — rebuild the Room mirror in the
        // background if it drifted from the files (e.g. a kill mid-write, or a restore).
        appScope.launch { runCatching { memoryRepository.reconcile() } }
        // Automation webhook (v4): observe the enable/allow-LAN prefs and run the embedded
        // HTTP server while the process is alive. No-op until the user enables it in Settings.
        webhookServerController.bind()
    }
}
