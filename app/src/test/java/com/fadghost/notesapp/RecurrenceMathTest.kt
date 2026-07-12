package com.fadghost.notesapp

import com.fadghost.notesapp.calendar.RecurrenceMath
import com.fadghost.notesapp.data.db.entity.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Recurrence maths, focused on DST correctness (PLAN.md §15 — "recurrence
 * next-occurrence across a DST boundary"). A daily reminder must keep its local
 * clock time even when the day it lands on is 23 or 25 hours long.
 */
class RecurrenceMathTest {

    private val ny = ZoneId.of("America/New_York")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, min: Int, zone: ZoneId): Long =
        LocalDateTime.of(y, mo, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun localTime(ms: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()

    @Test fun daily_across_spring_forward_keeps_local_time() {
        // US DST begins 2026-03-08 02:00; that local day is only 23 hours long.
        val start = millis(2026, 3, 7, 9, 0, ny)
        val next = RecurrenceMath.nextOccurrence(start, ny, Recurrence.DAILY)
        assertEquals(LocalTime.of(9, 0), localTime(next, ny))
        assertEquals(23 * 3_600_000L, next - start) // 23h, not 24h
    }

    @Test fun daily_across_fall_back_keeps_local_time() {
        // US DST ends 2026-11-01 02:00; that local day is 25 hours long.
        val start = millis(2026, 10, 31, 9, 0, ny)
        val next = RecurrenceMath.nextOccurrence(start, ny, Recurrence.DAILY)
        assertEquals(LocalTime.of(9, 0), localTime(next, ny))
        assertEquals(25 * 3_600_000L, next - start) // 25h, not 24h
    }

    @Test fun weekly_keeps_local_time_over_dst() {
        val start = millis(2026, 3, 5, 8, 30, ny) // Thursday before spring-forward
        val next = RecurrenceMath.nextOccurrence(start, ny, Recurrence.WEEKLY)
        assertEquals(LocalTime.of(8, 30), localTime(next, ny))
        // 7 calendar days spanning the lost hour = 167 hours.
        assertEquals(167 * 3_600_000L, next - start)
    }

    @Test fun monthly_advances_one_month() {
        val start = millis(2026, 1, 15, 12, 0, ny)
        val next = RecurrenceMath.nextOccurrence(start, ny, Recurrence.MONTHLY)
        val ldt = Instant.ofEpochMilli(next).atZone(ny).toLocalDateTime()
        assertEquals(2, ldt.monthValue)
        assertEquals(15, ldt.dayOfMonth)
        assertEquals(LocalTime.of(12, 0), ldt.toLocalTime())
    }

    @Test fun none_returns_same_instant() {
        val start = millis(2026, 6, 1, 10, 0, ny)
        assertEquals(start, RecurrenceMath.nextOccurrence(start, ny, Recurrence.NONE))
    }

    @Test fun nextFrom_skips_past_occurrences() {
        val seed = millis(2026, 6, 1, 7, 0, ny)
        val now = millis(2026, 6, 4, 12, 0, ny) // 3+ days later
        val next = RecurrenceMath.nextFrom(seed, ny, Recurrence.DAILY, now)
        val ldt = Instant.ofEpochMilli(next).atZone(ny).toLocalDateTime()
        assertEquals(5, ldt.dayOfMonth)          // first 07:00 strictly after now
        assertEquals(LocalTime.of(7, 0), ldt.toLocalTime())
    }

    @Test fun nextFrom_after_downtime_lands_on_single_next_slot_no_burst() {
        // Audit M1: a daily reminder whose device was off for a week. When it finally
        // fires, advancing must jump straight to the first slot AFTER now — not replay
        // every missed day one alarm at a time (which would be a notification burst).
        val seed = millis(2026, 6, 1, 9, 0, ny)   // last-known slot, a week stale
        val now = millis(2026, 6, 8, 10, 0, ny)   // 7 days of downtime, past today's 9am
        val next = RecurrenceMath.nextFrom(seed, ny, Recurrence.DAILY, now)

        // Strictly in the future, and it's the very next slot (tomorrow 09:00) — the
        // slot one step earlier sits at/before now, proving exactly one is pending.
        assertTrue(next > now)
        val prev = Instant.ofEpochMilli(next).atZone(ny).minusDays(1).toInstant().toEpochMilli()
        assertTrue(prev <= now)
        val ldt = Instant.ofEpochMilli(next).atZone(ny).toLocalDateTime()
        assertEquals(9, ldt.dayOfMonth)           // 2026-06-09 09:00, one slot past now
        assertEquals(LocalTime.of(9, 0), ldt.toLocalTime())
    }

    @Test fun occurrencesInRange_counts_daily() {
        val seed = millis(2026, 6, 1, 9, 0, ny)
        val rangeStart = millis(2026, 6, 1, 0, 0, ny)
        val rangeEnd = millis(2026, 6, 8, 0, 0, ny) // 7-day window
        val list = RecurrenceMath.occurrencesInRange(seed, ny, Recurrence.DAILY, rangeStart, rangeEnd)
        assertEquals(7, list.size)
    }

    @Test fun occurrencesInRange_none_only_if_inside() {
        val seed = millis(2026, 6, 3, 9, 0, ny)
        val inside = RecurrenceMath.occurrencesInRange(
            seed, ny, Recurrence.NONE,
            millis(2026, 6, 1, 0, 0, ny), millis(2026, 6, 8, 0, 0, ny)
        )
        assertEquals(1, inside.size)
        val outside = RecurrenceMath.occurrencesInRange(
            seed, ny, Recurrence.NONE,
            millis(2026, 7, 1, 0, 0, ny), millis(2026, 7, 8, 0, 0, ny)
        )
        assertEquals(0, outside.size)
    }
}
