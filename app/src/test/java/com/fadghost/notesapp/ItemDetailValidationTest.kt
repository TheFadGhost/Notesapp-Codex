package com.fadghost.notesapp

import com.fadghost.notesapp.ui.calendar.CalendarKind
import com.fadghost.notesapp.ui.calendar.ItemDetailValidation
import com.fadghost.notesapp.ui.calendar.ItemDetailValidation.Result
import com.fadghost.notesapp.data.db.entity.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Save-gate for the calendar create/edit sheet (ux.md P1-4). Pure + time-injected,
 * so blank-title, past-time, and valid cases are all deterministic.
 */
class ItemDetailValidationTest {

    private val now = 1_700_000_000_000L

    @Test fun blankTitle_isBlocked_forReminder() {
        assertEquals(
            Result.BLANK_TITLE,
            ItemDetailValidation.canSave(CalendarKind.REMINDER, "   ", now + 60_000L, now)
        )
    }

    @Test fun blankTitle_isBlocked_forEvent() {
        assertEquals(
            Result.BLANK_TITLE,
            ItemDetailValidation.canSave(CalendarKind.EVENT, "", now + 60_000L, now)
        )
    }

    @Test fun blankTitle_takesPriorityOverPastTime() {
        assertEquals(
            Result.BLANK_TITLE,
            ItemDetailValidation.canSave(CalendarKind.REMINDER, "", now - 60_000L, now)
        )
    }

    @Test fun pastTime_isBlocked_forReminder() {
        assertEquals(
            Result.PAST_TIME,
            ItemDetailValidation.canSave(CalendarKind.REMINDER, "Call mum", now - 1L, now)
        )
    }

    @Test fun pastTime_isAllowed_forEvent() {
        // Events may legitimately be logged in the past.
        assertEquals(
            Result.OK,
            ItemDetailValidation.canSave(CalendarKind.EVENT, "Standup", now - 60_000L, now)
        )
    }

    @Test fun nonRepeatingEvent_withPastAlert_isBlocked() {
        assertEquals(
            Result.PAST_NOTIFICATION,
            ItemDetailValidation.canSave(
                CalendarKind.EVENT,
                "Standup",
                now + 10 * 60_000L,
                now,
                Recurrence.NONE,
                notificationLeadMinutes = 30
            )
        )
    }

    @Test fun nonRepeatingEvent_withAlertExactlyNow_isBlocked() {
        assertEquals(
            Result.PAST_NOTIFICATION,
            ItemDetailValidation.canSave(
                CalendarKind.EVENT,
                "Standup",
                now + 10 * 60_000L,
                now,
                Recurrence.NONE,
                notificationLeadMinutes = 10
            )
        )
    }

    @Test fun eventWithAlertsOff_canBeLoggedInPast() {
        assertEquals(
            Result.OK,
            ItemDetailValidation.canSave(
                CalendarKind.EVENT,
                "Standup",
                now - 60_000L,
                now,
                Recurrence.NONE,
                notificationLeadMinutes = null
            )
        )
    }

    @Test fun repeatingEvent_allowsPastBaseAlertBecauseNextOccurrenceIsScheduled() {
        assertEquals(
            Result.OK,
            ItemDetailValidation.canSave(
                CalendarKind.EVENT,
                "Standup",
                now - 60_000L,
                now,
                Recurrence.DAILY,
                notificationLeadMinutes = 30
            )
        )
    }

    @Test fun futureReminder_isOk() {
        assertEquals(
            Result.OK,
            ItemDetailValidation.canSave(CalendarKind.REMINDER, "Gym", now + 3_600_000L, now)
        )
    }

    @Test fun exactlyNow_reminder_isOk() {
        // Not strictly in the past (whenMillis == now), so it saves.
        assertEquals(
            Result.OK,
            ItemDetailValidation.canSave(CalendarKind.REMINDER, "Now", now, now)
        )
    }
}
