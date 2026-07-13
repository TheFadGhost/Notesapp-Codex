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

/** Delivers one reminder slot exactly once, then advances recurring rows. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (id <= 0L) return
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            var claimedAt: Long? = null
            var completed = false
            try {
                if (!ReminderNotifier.canNotify(context)) return@launch
                val reminder = ep.reminderDao().getById(id) ?: return@launch
                if (reminder.done) return@launch
                val effectiveAt = reminder.snoozedUntil ?: reminder.triggerAt
                val requestedAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, -1L)
                    .takeIf { it > 0L } ?: effectiveAt
                if (ep.reminderDao().claimNotification(id, requestedAt) != 1) return@launch
                claimedAt = requestedAt

                val liveSourceNoteId = reminder.sourceNoteId?.takeIf { noteId ->
                    ep.noteDao().getById(noteId)?.let { it.deletedAt == null } == true
                }
                if (!ReminderNotifier.notify(context, reminder, liveSourceNoteId)) return@launch

                if (reminder.recurrence != Recurrence.NONE) {
                    val zone = runCatching { ZoneId.of(reminder.timezone) }
                        .getOrDefault(ZoneId.systemDefault())
                    val next = RecurrenceMath.nextFrom(
                        reminder.triggerAt,
                        zone,
                        reminder.recurrence,
                        System.currentTimeMillis()
                    )
                    ep.reminderDao().reschedule(id, next, null)
                    ep.alarmScheduler().scheduleReminder(
                        reminder.copy(
                            triggerAt = next,
                            snoozedUntil = null,
                            lastNotifiedTriggerAt = requestedAt
                        )
                    )
                }
                completed = true
            } finally {
                val claim = claimedAt
                if (claim != null && !completed) {
                    runCatching { ep.reminderDao().releaseNotificationClaim(id, claim) }
                }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.fadghost.notesapp.alarm.FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
