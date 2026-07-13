package com.fadghost.notesapp

import com.fadghost.notesapp.calendar.EventNotificationMath
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Recurrence
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventNotificationMathTest {
    private val zone = ZoneId.of("Europe/London")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        startAt: Long,
        lead: Int? = 30,
        recurrence: Recurrence = Recurrence.NONE,
        lastNotifiedOccurrenceAt: Long? = null
    ) = Event(
        id = 42,
        title = "Dentist",
        startAt = startAt,
        endAt = startAt + 3_600_000L,
        timezone = zone.id,
        recurrence = recurrence,
        notificationLeadMinutes = lead,
        lastNotifiedOccurrenceAt = lastNotifiedOccurrenceAt
    )

    @Test fun alertsOff_hasNoAlarm() {
        assertNull(EventNotificationMath.nextAlarm(event(millis(2026, 7, 14, 9), lead = null), millis(2026, 7, 13, 9)))
    }

    @Test fun oneShot_usesConfiguredLead() {
        val start = millis(2026, 7, 14, 9)
        val alarm = EventNotificationMath.nextAlarm(event(start, lead = 30), millis(2026, 7, 13, 9))!!

        assertEquals(start - 30 * 60_000L, alarm.fireAtMillis)
        assertEquals(start, alarm.occurrenceAtMillis)
        assertEquals(30, alarm.leadMinutes)
    }

    @Test fun missedLeadBeforeOccurrence_catchesUpInOneSecond() {
        val start = millis(2026, 7, 14, 9)
        val now = millis(2026, 7, 14, 8, 50)
        val alarm = EventNotificationMath.nextAlarm(event(start, lead = 30), now)!!

        assertEquals(now + EventNotificationMath.MIN_FUTURE_DELAY_MS, alarm.fireAtMillis)
        assertEquals(start, alarm.occurrenceAtMillis)
    }

    @Test fun deliveredOneShot_doesNotRearm() {
        val start = millis(2026, 7, 14, 9)
        assertNull(
            EventNotificationMath.nextAlarm(
                event(start, lastNotifiedOccurrenceAt = start),
                millis(2026, 7, 13, 9)
            )
        )
    }

    @Test fun deliveredRecurringOccurrence_advancesOneSlot() {
        val seed = millis(2026, 7, 13, 9)
        val upcoming = millis(2026, 7, 14, 9)
        val alarm = EventNotificationMath.nextAlarm(
            event(seed, recurrence = Recurrence.DAILY, lastNotifiedOccurrenceAt = upcoming),
            millis(2026, 7, 13, 12)
        )!!

        assertEquals(millis(2026, 7, 15, 9), alarm.occurrenceAtMillis)
    }

    @Test fun dailyRecurrence_preservesWallClockAcrossDstStart() {
        val seed = millis(2026, 3, 28, 9)
        val next = millis(2026, 3, 29, 9)
        val alarm = EventNotificationMath.nextAlarm(
            event(seed, lead = 0, recurrence = Recurrence.DAILY),
            millis(2026, 3, 28, 12)
        )!!

        assertEquals(next, alarm.occurrenceAtMillis)
        assertEquals(23 * 3_600_000L, next - seed)
        assertEquals(9, java.time.Instant.ofEpochMilli(next).atZone(zone).hour)
        assertTrue(EventNotificationMath.isCurrentOccurrence(event(seed, recurrence = Recurrence.DAILY), next))
    }
}
