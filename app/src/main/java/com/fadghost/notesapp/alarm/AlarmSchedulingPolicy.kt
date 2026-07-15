package com.fadghost.notesapp.alarm

import com.fadghost.notesapp.data.db.entity.Reminder

enum class AlarmPrecision { EXACT, INEXACT }

data class AlarmRequest(val atMillis: Long, val precision: AlarmPrecision)

/** Android-free policy for deciding whether and how a reminder is armed. */
object AlarmSchedulingPolicy {
    fun request(reminder: Reminder, exactAllowed: Boolean): AlarmRequest? {
        if (reminder.done || reminder.alarmFired || reminder.id <= 0L) return null
        return AlarmRequest(
            atMillis = reminder.snoozedUntil ?: reminder.triggerAt,
            precision = if (exactAllowed) AlarmPrecision.EXACT else AlarmPrecision.INEXACT
        )
    }
}
