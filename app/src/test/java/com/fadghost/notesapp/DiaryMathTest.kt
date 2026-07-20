package com.fadghost.notesapp

import com.fadghost.notesapp.ui.diary.DiaryMath
import com.fadghost.notesapp.ui.diary.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Unit coverage for the pure diary maths (PLAN.md §7 / §15). */
class DiaryMathTest {

    private fun dates(vararg iso: String) = iso.map { LocalDate.parse(it) }.toSet()

    // --- current streak ---------------------------------------------------------

    @Test fun currentStreak_countsRunEndingToday() {
        val today = LocalDate.parse("2026-07-11")
        val d = dates("2026-07-09", "2026-07-10", "2026-07-11")
        assertEquals(3, DiaryMath.currentStreak(d, today))
    }

    @Test fun currentStreak_todayPending_countsFromYesterday() {
        val today = LocalDate.parse("2026-07-11")
        // No entry today yet, but a run through yesterday.
        val d = dates("2026-07-08", "2026-07-09", "2026-07-10")
        assertEquals(3, DiaryMath.currentStreak(d, today))
    }

    @Test fun currentStreak_brokenByGap_isZero() {
        val today = LocalDate.parse("2026-07-11")
        // Last entry two days ago -> neither today nor yesterday present.
        val d = dates("2026-07-01", "2026-07-09")
        assertEquals(0, DiaryMath.currentStreak(d, today))
    }

    @Test fun currentStreak_emptyHistory_isZero() {
        assertEquals(0, DiaryMath.currentStreak(emptySet(), LocalDate.parse("2026-07-11")))
    }

    @Test fun currentStreak_singleTodayEntry_isOne() {
        val today = LocalDate.parse("2026-07-11")
        assertEquals(1, DiaryMath.currentStreak(dates("2026-07-11"), today))
    }

    // --- longest streak ---------------------------------------------------------

    @Test fun longestStreak_findsBestRunAmongGaps() {
        val d = dates(
            "2026-01-01", "2026-01-02",                       // run of 2
            "2026-02-10", "2026-02-11", "2026-02-12", "2026-02-13", // run of 4
            "2026-03-01"                                       // run of 1
        )
        assertEquals(4, DiaryMath.longestStreak(d))
    }

    @Test fun longestStreak_empty_isZero() {
        assertEquals(0, DiaryMath.longestStreak(emptySet()))
    }

    @Test fun longestStreak_single_isOne() {
        assertEquals(1, DiaryMath.longestStreak(dates("2026-05-05")))
    }

    @Test fun longestStreak_spansMonthBoundary() {
        val d = dates("2026-01-30", "2026-01-31", "2026-02-01", "2026-02-02")
        assertEquals(4, DiaryMath.longestStreak(d))
    }

    // --- heat-map bucketing -----------------------------------------------------

    @Test fun intensityFor_bucketsByWeight() {
        assertEquals(0, DiaryMath.intensityFor(0))
        assertEquals(1, DiaryMath.intensityFor(1))
        assertEquals(1, DiaryMath.intensityFor(19))
        assertEquals(2, DiaryMath.intensityFor(20))
        assertEquals(2, DiaryMath.intensityFor(59))
        assertEquals(3, DiaryMath.intensityFor(60))
        assertEquals(3, DiaryMath.intensityFor(149))
        assertEquals(4, DiaryMath.intensityFor(150))
        assertEquals(4, DiaryMath.intensityFor(10_000))
    }

    @Test fun heatMapColumns_hasExactDimensions() {
        val end = LocalDate.parse("2026-07-11") // Saturday
        val cols = DiaryMath.heatMapColumns(end, weeks = 22)
        assertEquals(22, cols.size)
        assertTrue(cols.all { it.size == 7 })
    }

    @Test fun heatMapColumns_lastColumnContainsEnd_andRowsAreWeekAligned() {
        val end = LocalDate.parse("2026-07-11") // Saturday
        val cols = DiaryMath.heatMapColumns(end, weeks = 4, firstDayOfWeek = DayOfWeek.MONDAY)
        val lastColumn = cols.last()
        assertTrue(end in lastColumn)
        // Row 0 must be a Monday, row 6 a Sunday, and days consecutive within a column.
        assertEquals(DayOfWeek.MONDAY, lastColumn.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, lastColumn.last().dayOfWeek)
        for (i in 1 until lastColumn.size) {
            assertEquals(lastColumn[i - 1].plusDays(1), lastColumn[i])
        }
    }

    @Test fun heatMapColumns_columnsAreOneWeekApart() {
        val end = LocalDate.parse("2026-07-11")
        val cols = DiaryMath.heatMapColumns(end, weeks = 3)
        assertEquals(cols[0].first().plusWeeks(1), cols[1].first())
        assertEquals(cols[1].first().plusWeeks(1), cols[2].first())
    }

    @Test fun heatCells_appliesWeightsToMatchingDates() {
        val end = LocalDate.parse("2026-07-11")
        val weights = mapOf(
            end to 200,                 // level 4
            end.minusDays(1) to 30      // level 2
        )
        val cells = DiaryMath.heatCells(end, weeks = 6, weightByDate = weights)
        val flat = cells.flatten().associateBy { it.date }
        assertEquals(4, flat.getValue(end).level)
        assertEquals(2, flat.getValue(end.minusDays(1)).level)
        assertEquals(0, flat.getValue(end.minusDays(2)).level)
    }

    // --- on this day ------------------------------------------------------------

    @Test fun onThisDay_simpleCase() {
        val today = LocalDate.parse("2026-07-11")
        val r = DiaryMath.onThisDay(today)
        assertEquals(LocalDate.parse("2026-06-11"), r.monthAgo)
        assertEquals(LocalDate.parse("2025-07-11"), r.yearAgo)
    }

    @Test fun onThisDay_clampsShortMonth() {
        val today = LocalDate.parse("2024-03-31")
        // Feb 2024 is a leap February -> clamp to the 29th.
        assertEquals(LocalDate.parse("2024-02-29"), DiaryMath.onThisDay(today).monthAgo)
    }

    @Test fun onThisDay_leapDayMinusYearClampsToFeb28() {
        val today = LocalDate.parse("2024-02-29")
        assertEquals(LocalDate.parse("2023-02-28"), DiaryMath.onThisDay(today).yearAgo)
    }

    // --- mood scale -------------------------------------------------------------

    @Test fun mood_roundTripsThroughScore() {
        Mood.entries.forEach { assertEquals(it, Mood.fromScore(it.score)) }
        assertNull(Mood.fromScore(null))
        assertNull(Mood.fromScore(99))
    }

    // --- streak grace (IDEAS #51) ----------------------------------------------

    @Test fun grace_unbrokenRun_matchesStrictStreak_noGraceUsed() {
        val today = LocalDate.parse("2026-07-11")
        val d = dates("2026-07-09", "2026-07-10", "2026-07-11")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(3, r.count)
        assertEquals(false, r.graceUsed)
    }

    @Test fun grace_bridgesOneMissedDayInsideRun() {
        val today = LocalDate.parse("2026-07-11")
        // 8th, 9th written; 10th missed; 11th written → grace keeps the run at 3.
        val d = dates("2026-07-08", "2026-07-09", "2026-07-11")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(3, r.count)
        assertEquals(true, r.graceUsed)
    }

    @Test fun grace_doesNotBridgeTwoMissedDays() {
        val today = LocalDate.parse("2026-07-11")
        // 11th written, 10th+9th missed, 8th written → only today counts.
        val d = dates("2026-07-08", "2026-07-11")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(1, r.count)
        assertEquals(false, r.graceUsed)
    }

    @Test fun grace_onlyOneGapPerRun() {
        val today = LocalDate.parse("2026-07-11")
        // Two separate single-day gaps: only the first (most recent) is forgiven.
        val d = dates("2026-07-05", "2026-07-07", "2026-07-09", "2026-07-10", "2026-07-11")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(4, r.count) // 11,10,9 + bridged gap on the 8th + 7; stops before the gap on the 6th
        assertEquals(true, r.graceUsed)
    }

    @Test fun grace_todayPendingAndYesterdayMissed_reachesRun() {
        val today = LocalDate.parse("2026-07-11")
        // Nothing today or yesterday, but a run through the 9th: grace reaches it.
        val d = dates("2026-07-08", "2026-07-09")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(2, r.count)
        assertEquals(true, r.graceUsed)
    }

    @Test fun grace_noEntriesAnywhereNearToday_isZero() {
        val today = LocalDate.parse("2026-07-11")
        val d = dates("2026-07-01")
        val r = DiaryMath.currentStreakWithGrace(d, today)
        assertEquals(0, r.count)
        assertEquals(false, r.graceUsed)
    }
}
