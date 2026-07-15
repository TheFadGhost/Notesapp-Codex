package com.fadghost.notesapp

import com.fadghost.notesapp.alarm.AlarmPrecision
import com.fadghost.notesapp.alarm.AlarmSchedulingPolicy
import com.fadghost.notesapp.data.db.entity.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmSchedulingPolicyTest {
    private fun reminder(
        done: Boolean = false,
        fired: Boolean = false,
        snoozedUntil: Long? = null
    ) = Reminder(
        id = 42L,
        title = "Call mum",
        triggerAt = 10_000L,
        timezone = "Europe/London",
        done = done,
        snoozedUntil = snoozedUntil,
        alarmFired = fired
    )

    @Test fun exactAccess_usesExactAlarmAtTrigger() {
        assertEquals(
            com.fadghost.notesapp.alarm.AlarmRequest(10_000L, AlarmPrecision.EXACT),
            AlarmSchedulingPolicy.request(reminder(), exactAllowed = true)
        )
    }

    @Test fun missingExactAccess_degradesToInexactAtSameTrigger() {
        assertEquals(
            com.fadghost.notesapp.alarm.AlarmRequest(10_000L, AlarmPrecision.INEXACT),
            AlarmSchedulingPolicy.request(reminder(), exactAllowed = false)
        )
    }

    @Test fun snoozeOverridesOriginalTriggerWithoutChangingPrecision() {
        assertEquals(
            com.fadghost.notesapp.alarm.AlarmRequest(25_000L, AlarmPrecision.EXACT),
            AlarmSchedulingPolicy.request(reminder(snoozedUntil = 25_000L), exactAllowed = true)
        )
    }

    @Test fun completedOrAlreadyFiredReminderIsNotRearmed() {
        assertNull(AlarmSchedulingPolicy.request(reminder(done = true), exactAllowed = true))
        assertNull(AlarmSchedulingPolicy.request(reminder(fired = true), exactAllowed = true))
    }
}
