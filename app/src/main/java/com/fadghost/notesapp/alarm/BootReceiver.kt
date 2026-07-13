package com.fadghost.notesapp.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fadghost.notesapp.notify.NotificationChannels
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules every pending exact alarm after a reboot or an app update (PLAN.md
 * §8 — "alarms rescheduled on BOOT_COMPLETED and after app update"). Exact alarms
 * do not survive either event, so without this reminders would silently stop firing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED &&
                    !epCanSchedule(context)
                ) return
                NotificationChannels.ensure(context)
                val pending = goAsync()
                val ep = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ep.alarmScheduler().rescheduleAll()
                        ep.eventAlarm().rescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun epCanSchedule(context: Context): Boolean {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AlarmEntryPoint::class.java
        )
        return ep.alarmScheduler().canExact()
    }
}
