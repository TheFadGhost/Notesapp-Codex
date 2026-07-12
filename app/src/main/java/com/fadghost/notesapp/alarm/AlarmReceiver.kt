package com.fadghost.notesapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fadghost.notesapp.calendar.RecurrenceMath
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.notify.ReminderNotifier
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Fires when a reminder's exact alarm goes off (PLAN.md §8). Posts the notification
 * and, for a repeating reminder, advances the row to the next occurrence and rearms
 * the alarm — "schedule next occurrence on fire", no edit-this-vs-all. Uses
 * [BroadcastReceiver.goAsync] to keep the process alive for the short DB write.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (id <= 0) return

        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminder = ep.reminderDao().getById(id)
                if (reminder != null && !reminder.done) {
                    ReminderNotifier.notify(context, reminder)
                    if (reminder.recurrence != Recurrence.NONE) {
                        val zone = runCatching { ZoneId.of(reminder.timezone) }.getOrDefault(ZoneId.systemDefault())
                        // Anchor on the true recurrence slot (triggerAt, never a snoozed
                        // time) and jump straight to the first occurrence after *now*:
                        // after downtime this catches up in one hop instead of firing a
                        // burst of back-to-back missed occurrences (audit M1).
                        val next = RecurrenceMath.nextFrom(
                            reminder.triggerAt, zone, reminder.recurrence, System.currentTimeMillis()
                        )
                        ep.reminderDao().reschedule(id, next, null)
                        ep.alarmScheduler().scheduleReminder(reminder.copy(triggerAt = next, snoozedUntil = null))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.fadghost.notesapp.alarm.FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
