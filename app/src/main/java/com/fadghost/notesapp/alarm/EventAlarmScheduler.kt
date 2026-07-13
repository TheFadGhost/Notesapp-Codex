package com.fadghost.notesapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fadghost.notesapp.calendar.EventNotificationMath
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.notify.EventNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Exact-alarm scheduler for optional event lead-time notifications. */
@Singleton
class EventAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventDao: EventDao
) : EventAlarm {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun scheduleEvent(event: Event) {
        val next = EventNotificationMath.nextAlarm(event, System.currentTimeMillis())
        if (next == null) {
            cancelPendingAlarm(event.id)
            return
        }
        val pi = pendingIntent(
            eventId = event.id,
            occurrenceAt = next.occurrenceAtMillis,
            leadMinutes = next.leadMinutes,
            create = true
        ) ?: return
        try {
            if (canExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAtMillis, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAtMillis, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAtMillis, pi)
        }
    }

    override fun cancelEvent(eventId: Long) {
        cancelPendingAlarm(eventId)
        EventNotifier.cancel(context, eventId)
    }

    private fun cancelPendingAlarm(eventId: Long) {
        pendingIntent(eventId, occurrenceAt = 0L, leadMinutes = 0, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    override suspend fun rescheduleAll() {
        eventDao.allWithNotifications().forEach(::scheduleEvent)
    }

    override fun canExact(): Boolean = alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(
        eventId: Long,
        occurrenceAt: Long,
        leadMinutes: Int,
        create: Boolean
    ): PendingIntent? {
        val intent = Intent(context, EventAlarmReceiver::class.java).apply {
            action = EventAlarmReceiver.ACTION_FIRE
            data = Uri.parse("${context.packageName}://alarm/event/$eventId")
            putExtra(EventAlarmReceiver.EXTRA_EVENT_ID, eventId)
            putExtra(EventAlarmReceiver.EXTRA_OCCURRENCE_AT, occurrenceAt)
            putExtra(EventAlarmReceiver.EXTRA_LEAD_MINUTES, leadMinutes)
        }
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, stableRequestCode(eventId), intent, flags)
    }

    private fun stableRequestCode(id: Long): Int = (id xor (id ushr 32)).toInt()
}
