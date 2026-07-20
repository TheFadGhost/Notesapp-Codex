package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.cost.CostAccumulator
import com.fadghost.notesapp.data.ai.cost.CostAccumulator.BudgetLevel
import com.fadghost.notesapp.data.prefs.BackupPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the AI budget cap (IDEAS #26) and backup nudge (IDEAS #83). */
class BudgetAndBackupNudgeTest {

    // --- budgetLevel ------------------------------------------------------------

    @Test fun budgetLevel_noCap_isNone() {
        assertEquals(BudgetLevel.NONE, CostAccumulator.budgetLevel(5.0, 0.0))
        assertEquals(BudgetLevel.NONE, CostAccumulator.budgetLevel(5.0, -1.0))
    }

    @Test fun budgetLevel_underCap() {
        assertEquals(BudgetLevel.UNDER, CostAccumulator.budgetLevel(0.5, 10.0))
    }

    @Test fun budgetLevel_nearCap_at80Percent() {
        assertEquals(BudgetLevel.NEAR, CostAccumulator.budgetLevel(8.0, 10.0))
        assertEquals(BudgetLevel.NEAR, CostAccumulator.budgetLevel(9.99, 10.0))
    }

    @Test fun budgetLevel_overCap_atAndPastCap() {
        assertEquals(BudgetLevel.OVER, CostAccumulator.budgetLevel(10.0, 10.0))
        assertEquals(BudgetLevel.OVER, CostAccumulator.budgetLevel(12.0, 10.0))
    }

    @Test fun budgetLevel_zeroSpendWithCap_isUnder() {
        assertEquals(BudgetLevel.UNDER, CostAccumulator.budgetLevel(0.0, 1.0))
    }

    // --- backup nudge -----------------------------------------------------------

    private val day = 86_400_000L

    @Test fun describe_neverBackedUp() {
        assertEquals("Never backed up yet", BackupPreferences.describe(0L, 100L))
    }

    @Test fun describe_todayYesterdayAndDays() {
        val now = 100L * day
        assertEquals("Last backup: today", BackupPreferences.describe(now - day / 2, now))
        assertEquals("Last backup: yesterday", BackupPreferences.describe(now - day, now))
        assertEquals("Last backup: 12 days ago", BackupPreferences.describe(now - 12 * day, now))
    }

    @Test fun isStale_neverOrOld() {
        val now = 100L * day
        assertTrue(BackupPreferences.isStale(0L, now))
        assertTrue(BackupPreferences.isStale(now - 15 * day, now))
        assertFalse(BackupPreferences.isStale(now - 13 * day, now))
    }
}
