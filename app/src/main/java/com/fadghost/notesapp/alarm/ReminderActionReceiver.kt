package com.fadghost.notesapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fadghost.notesapp.calendar.SnoozeMath
import com.fadghost.notesapp.notify.ReminderNotifier
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the notification action buttons (PLAN.md §8): Done marks the row complete
 * and clears the alarm; Snooze reschedules the alarm a fixed offset out and updates
 * the row. All mutate the DB then reschedule, then dismiss the notification.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderNotifier.EXTRA_REMINDER_ID, -1L)
        if (id <= 0) return
        val action = intent.action ?: return

        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_DONE -> {
                        ep.reminderDao().setDone(id, true)
                        ep.alarmScheduler().cancelReminder(id)
                    }
                    ACTION_SNOOZE_10 -> snooze(ep, id, SnoozeMath.SNOOZE_10_MIN_MS)
                    ACTION_SNOOZE_60 -> snooze(ep, id, SnoozeMath.SNOOZE_1_HOUR_MS)
                }
                ReminderNotifier.cancel(context, id)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun snooze(ep: AlarmEntryPoint, id: Long, durationMs: Long) {
        val reminder = ep.reminderDao().getById(id) ?: return
        val until = SnoozeMath.snoozeUntil(System.currentTimeMillis(), durationMs)
        // Keep triggerAt at the true recurrence slot; only snoozedUntil moves. The
        // scheduler fires at snoozedUntil, but recurrence still advances from the
        // original slot so snoozing never drifts the cadence (audit M1).
        ep.reminderDao().reschedule(id, reminder.triggerAt, until)
        ep.reminderDao().setAlarmFired(id, false)
        ep.alarmScheduler().scheduleReminder(
            reminder.copy(snoozedUntil = until, done = false, alarmFired = false)
        )
    }

    companion object {
        const val ACTION_DONE = "com.fadghost.notesapp.alarm.DONE"
        const val ACTION_SNOOZE_10 = "com.fadghost.notesapp.alarm.SNOOZE_10"
        const val ACTION_SNOOZE_60 = "com.fadghost.notesapp.alarm.SNOOZE_60"
    }
}
