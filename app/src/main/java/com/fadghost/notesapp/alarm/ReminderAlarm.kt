package com.fadghost.notesapp.alarm

import com.fadghost.notesapp.data.db.entity.Reminder

/**
 * Scheduling seam over the exact-alarm plumbing (PLAN.md §8). Extracted as an
 * interface so collaborators outside the `alarm` package (e.g. the AI layer that
 * arms alarms for extracted reminders, audit H1) depend on this rather than the
 * Android-bound [AlarmScheduler], keeping their wiring unit-testable with a fake.
 */
interface ReminderAlarm {
    /** (Re)arm the exact alarm for [reminder] at its next fire time. */
    fun scheduleReminder(reminder: Reminder)

    /** Cancel the pending alarm for [reminderId]. */
    fun cancelReminder(reminderId: Long)

    /** Rearm every not-done reminder — called after reboot / app update / cold start. */
    suspend fun rescheduleAll()

    /** Whether the OS currently allows exact alarms. */
    fun canExact(): Boolean
}
