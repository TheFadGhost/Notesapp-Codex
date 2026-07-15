package com.fadghost.notesapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.notify.ReminderNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single abstraction over [AlarmManager] exact alarms (PLAN.md §8 — reminders must
 * fire at a clock time). Uses [AlarmManager.setExactAndAllowWhileIdle] so Doze
 * can't defer a reminder. The app declares USE_EXACT_ALARM (auto-granted for this
 * sideloaded build), but we still guard [AlarmManager.canScheduleExactAlarms] and
 * fall back to an inexact window rather than crashing on a locked-down OEM.
 *
 * Nothing here computes recurrence — the receiver advances the row and calls back
 * in via [scheduleReminder] with the next occurrence, keeping scheduling and the
 * DST-safe maths ([com.fadghost.notesapp.calendar.RecurrenceMath]) separate.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao
) : ReminderAlarm {
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /**
     * (Re)arm the exact alarm for [reminder]. Fires at [Reminder.snoozedUntil] when a
     * snooze is pending, otherwise at [Reminder.triggerAt] — the true recurrence slot.
     * Keeping the slot in [Reminder.triggerAt] (never overwritten by a snooze) is what
     * lets recurrence advance from the original clock time (audit M1).
     */
    override fun scheduleReminder(reminder: Reminder) {
        val at = ReminderAlarmMath.nextAlarmAt(reminder)
        if (at == null) {
            cancelReminder(reminder.id)
            return
        }
        val request = AlarmSchedulingPolicy.request(reminder, canExact())
        if (request == null) {
            cancelReminder(reminder.id)
            return
        }
        val pi = pendingIntent(reminder.id, at, create = true) ?: return
        try {
            if (request.precision == AlarmPrecision.EXACT) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atMillis, pi)
            } else {
                // Best-effort fallback: still fires, just not guaranteed to the second.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atMillis, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.atMillis, pi)
        }
    }

    override fun cancelReminder(reminderId: Long) {
        pendingIntent(reminderId, scheduledAt = 0L, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        ReminderNotifier.cancel(context, reminderId)
    }

    /** Rearm every not-done reminder — called after reboot / app update. */
    override suspend fun rescheduleAll() {
        reminderDao.allPending().forEach { scheduleReminder(it) }
    }

    override fun canExact(): Boolean = alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(reminderId: Long, scheduledAt: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("${context.packageName}://alarm/reminder/$reminderId")
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt)
        }
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, stableRequestCode(reminderId), intent, flags)
    }

    private fun stableRequestCode(id: Long): Int = (id xor (id ushr 32)).toInt()
}
