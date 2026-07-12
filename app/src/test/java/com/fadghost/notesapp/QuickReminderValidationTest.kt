package com.fadghost.notesapp

import com.fadghost.notesapp.ui.reminder.QuickReminderViewModel
import com.fadghost.notesapp.ui.reminder.ReminderValidation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure Create-button gate for the Quick-reminder dialog (ux.md P1-4). Deterministic
 * because now is injected. Order matters: a blank title fails before the past check.
 */
class QuickReminderValidationTest {

    private val now = 1_700_000_000_000L

    @Test fun blank_title_is_rejected() {
        assertEquals(
            ReminderValidation.BlankTitle,
            QuickReminderViewModel.validate("   ", now + 60_000L, now)
        )
    }

    @Test fun past_time_is_blocked() {
        assertEquals(
            ReminderValidation.PastTime,
            QuickReminderViewModel.validate("Call mum", now - 1L, now)
        )
    }

    @Test fun now_exactly_counts_as_past() {
        assertEquals(
            ReminderValidation.PastTime,
            QuickReminderViewModel.validate("Call mum", now, now)
        )
    }

    @Test fun valid_future_reminder_is_ok() {
        assertEquals(
            ReminderValidation.Ok,
            QuickReminderViewModel.validate("Call mum", now + 60_000L, now)
        )
    }

    @Test fun blank_title_beats_past_time() {
        assertEquals(
            ReminderValidation.BlankTitle,
            QuickReminderViewModel.validate("", now - 60_000L, now)
        )
    }
}
