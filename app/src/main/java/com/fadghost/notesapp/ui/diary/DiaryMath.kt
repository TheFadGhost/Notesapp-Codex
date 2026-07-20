package com.fadghost.notesapp.ui.diary

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pure-Kotlin diary maths (PLAN.md §7): streaks, contribution heat-map bucketing
 * and "On this day" date arithmetic. Deliberately free of Android/Compose imports
 * so it is exhaustively unit-tested on the JVM (see DiaryMathTest).
 */

/** Mood scale, stored verbatim in the existing [DiaryEntry.mood] column (0..4). */
enum class Mood(val score: Int, val label: String) {
    AWFUL(0, "Awful"),
    LOW(1, "Low"),
    OKAY(2, "Okay"),
    GOOD(3, "Good"),
    GREAT(4, "Great");

    companion object {
        fun fromScore(score: Int?): Mood? =
            score?.let { v -> entries.firstOrNull { it.score == v } }
    }
}

/** Current run (ending today, or yesterday while today is still "pending") + all-time best. */
data class DiaryStreaks(val current: Int, val longest: Int, val graceUsed: Boolean = false)

/** [currentStreakWithGrace] result: run length + whether a grace day bridged a gap. */
data class StreakResult(val count: Int, val graceUsed: Boolean)

/** One heat-map square: its calendar date and an accent-intensity level 0..4 (0 == no entry). */
data class HeatCell(val date: LocalDate, val level: Int)

/** Result of the "On this day" lookback — the two dates we resurface entries for. */
data class OnThisDayDates(val monthAgo: LocalDate, val yearAgo: LocalDate)

object DiaryMath {

    /**
     * Length of the consecutive-day run ending at [today]. If today has no entry yet
     * but yesterday does, the streak counts from yesterday (today-pending — a missing
     * today does not immediately break a streak). Returns 0 when neither today nor
     * yesterday has an entry.
     */
    fun currentStreak(dates: Set<LocalDate>, today: LocalDate): Int {
        val anchor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        var day = anchor
        while (day in dates) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /**
     * Streak grace (IDEAS #51): like [currentStreak], but ONE fully-missed day inside
     * the run is forgiven — the run continues across it (the missed day itself adds
     * nothing to the count). One busy day no longer wipes a 30-day habit. Grace also
     * covers the anchor: today pending + yesterday missed still keeps the run alive
     * from the day before. The all-time longest streak stays strict — history is
     * honest; forgiveness applies only to the run you're living in.
     */
    fun currentStreakWithGrace(dates: Set<LocalDate>, today: LocalDate): StreakResult {
        var graceLeft = 1
        var graceUsed = false
        val anchor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            // Today pending AND yesterday missed: spend the grace day to reach the run.
            today.minusDays(2) in dates -> {
                graceLeft = 0; graceUsed = true
                today.minusDays(2)
            }
            else -> return StreakResult(0, graceUsed = false)
        }
        var count = 0
        var day = anchor
        while (true) {
            if (day in dates) {
                count++
                day = day.minusDays(1)
            } else if (graceLeft > 0 && day.minusDays(1) in dates) {
                graceLeft = 0
                graceUsed = true
                day = day.minusDays(1)
            } else break
        }
        return StreakResult(count, graceUsed)
    }

    /** Longest consecutive-day run anywhere in the history. */
    fun longestStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.toSortedSet()
        var best = 1
        var run = 1
        var prev: LocalDate? = null
        for (day in sorted) {
            run = if (prev != null && prev.plusDays(1) == day) run + 1 else 1
            if (run > best) best = run
            prev = day
        }
        return best
    }

    /** Map a per-day weight (e.g. word count) to an accent-intensity bucket 0..4. */
    fun intensityFor(weight: Int): Int = when {
        weight <= 0 -> 0
        weight < 20 -> 1
        weight < 60 -> 2
        weight < 150 -> 3
        else -> 4
    }

    /**
     * GitHub-style week columns. Each column is one calendar week of 7 [LocalDate]s
     * (row 0 == [firstDayOfWeek]). Columns run past → present left-to-right; the last
     * column is the week containing [end]. Always returns exactly [weeks] columns of 7.
     */
    fun heatMapColumns(
        end: LocalDate,
        weeks: Int,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
    ): List<List<LocalDate>> {
        if (weeks <= 0) return emptyList()
        val shift = ((end.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val lastWeekStart = end.minusDays(shift.toLong())
        val firstWeekStart = lastWeekStart.minusWeeks((weeks - 1).toLong())
        return (0 until weeks).map { w ->
            val weekStart = firstWeekStart.plusWeeks(w.toLong())
            (0 until 7).map { weekStart.plusDays(it.toLong()) }
        }
    }

    /** [heatMapColumns] resolved to [HeatCell]s using [weightByDate] for intensity. */
    fun heatCells(
        end: LocalDate,
        weeks: Int,
        weightByDate: Map<LocalDate, Int>,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
    ): List<List<HeatCell>> =
        heatMapColumns(end, weeks, firstDayOfWeek).map { column ->
            column.map { date -> HeatCell(date, intensityFor(weightByDate[date] ?: 0)) }
        }

    /**
     * The two "On this day" anchor dates. java.time clamps short months (e.g. Mar 31
     * minus one month → Feb 28/29; a leap Feb 29 minus one year → Feb 28).
     */
    fun onThisDay(today: LocalDate): OnThisDayDates =
        OnThisDayDates(monthAgo = today.minusMonths(1), yearAgo = today.minusYears(1))
}
