package com.fadghost.notesapp

import com.fadghost.notesapp.alarm.ReminderAlarmMath
import com.fadghost.notesapp.data.db.entity.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderAlarmMathTest {
    private fun reminder(
        triggerAt: Long = 1_800_000_000_000L,
        done: Boolean = false,
        snoozedUntil: Long? = null,
        lastNotifiedTriggerAt: Long? = null
    ) = Reminder(
        id = 7,
        title = "Call dentist",
        triggerAt = triggerAt,
        timezone = "Europe/London",
        done = done,
        snoozedUntil = snoozedUntil,
        lastNotifiedTriggerAt = lastNotifiedTriggerAt
    )

    @Test fun pendingReminder_armsEffectiveTrigger() {
        assertEquals(1_800_000_000_000L, ReminderAlarmMath.nextAlarmAt(reminder()))
    }

    @Test fun deliveredOneShot_doesNotRearm() {
        assertNull(ReminderAlarmMath.nextAlarmAt(reminder(lastNotifiedTriggerAt = 1_800_000_000_000L)))
    }

    @Test fun newSnoozeSlot_armsEvenAfterOriginalWasDelivered() {
        assertEquals(
            1_800_000_600_000L,
            ReminderAlarmMath.nextAlarmAt(
                reminder(
                    snoozedUntil = 1_800_000_600_000L,
                    lastNotifiedTriggerAt = 1_800_000_000_000L
                )
            )
        )
    }

    @Test fun completedReminder_doesNotArm() {
        assertNull(ReminderAlarmMath.nextAlarmAt(reminder(done = true)))
    }
}
