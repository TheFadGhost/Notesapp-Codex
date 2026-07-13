package com.fadghost.notesapp.alarm

import com.fadghost.notesapp.data.db.entity.Reminder

/** Pure scheduling decision for reminders, including durable duplicate suppression. */
object ReminderAlarmMath {
    fun nextAlarmAt(reminder: Reminder): Long? {
        if (reminder.done) return null
        val effectiveAt = reminder.snoozedUntil ?: reminder.triggerAt
        return effectiveAt.takeUnless { reminder.lastNotifiedTriggerAt == it }
    }
}
