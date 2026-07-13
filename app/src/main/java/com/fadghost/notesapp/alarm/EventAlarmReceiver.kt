package com.fadghost.notesapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fadghost.notesapp.calendar.EventNotificationMath
import com.fadghost.notesapp.notify.EventNotifier
import com.fadghost.notesapp.notify.ReminderNotifier
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Claims and posts one event occurrence, then arms the next recurring occurrence. */
class EventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val occurrenceAt = intent.getLongExtra(EXTRA_OCCURRENCE_AT, -1L)
        val leadMinutes = intent.getIntExtra(EXTRA_LEAD_MINUTES, -1)
        if (eventId <= 0L || occurrenceAt <= 0L || leadMinutes < 0) return
        val pending = goAsync()
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            var claimed = false
            var completed = false
            try {
                if (!ReminderNotifier.canNotify(context)) return@launch
                val event = ep.eventDao().getById(eventId) ?: return@launch
                if (event.notificationLeadMinutes != leadMinutes) return@launch
                if (!EventNotificationMath.isCurrentOccurrence(event, occurrenceAt)) return@launch
                if (ep.eventDao().claimNotification(eventId, occurrenceAt) != 1) return@launch
                claimed = true
                if (!EventNotifier.notify(context, event, occurrenceAt)) return@launch
                ep.eventAlarm().scheduleEvent(event.copy(lastNotifiedOccurrenceAt = occurrenceAt))
                completed = true
            } finally {
                if (claimed && !completed) {
                    runCatching { ep.eventDao().releaseNotificationClaim(eventId, occurrenceAt) }
                }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.fadghost.notesapp.alarm.EVENT_FIRE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_OCCURRENCE_AT = "occurrence_at"
        const val EXTRA_LEAD_MINUTES = "lead_minutes"
    }
}
