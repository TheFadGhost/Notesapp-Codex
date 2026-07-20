package com.fadghost.notesapp.data.ai.cost

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * One row per AI call, storing OpenRouter's authoritative post-hoc cost
 * (PLAN.md §5/§7 — "read the usage field per response; store per-call cost
 * rows"). No pre-call estimation; [costUsd] comes straight from the API.
 */
@Entity(tableName = "AiCallCost", indices = [Index("createdAt")])
data class AiCallCost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** "cleanup" | "extract" (free text; kept simple). */
    val feature: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Double = 0.0,
    val noteId: Long? = null
)

/**
 * Pure cost accumulation (PLAN.md §5 — "this-month total + last-call chip").
 * Deterministic: month boundaries are passed in, no clock/zone dependency, so
 * it is unit-tested on the JVM.
 */
object CostAccumulator {

    data class Summary(
        val monthTotalUsd: Double,
        val monthCalls: Int,
        val lastCall: AiCallCost?
    )

    /** Sum [rows] whose createdAt is in [monthStart, nowInclusive]; newest is the last-call chip. */
    fun summarize(rows: List<AiCallCost>, monthStart: Long, nowInclusive: Long): Summary {
        var total = 0.0
        var count = 0
        var last: AiCallCost? = null
        for (r in rows) {
            if (r.createdAt in monthStart..nowInclusive) {
                total += r.costUsd
                count++
            }
            if (last == null || r.createdAt > last.createdAt) last = r
        }
        return Summary(monthTotalUsd = total, monthCalls = count, lastCall = last)
    }

    /** Epoch millis of the first instant of [now]'s calendar month in [zone]. */
    fun startOfMonth(now: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Budget standing for the monthly cap (IDEAS #26). NONE == no cap configured. */
    enum class BudgetLevel { NONE, UNDER, NEAR, OVER }

    /**
     * Pure cap check: no cap (<= 0) → NONE; at or past the cap → OVER; within
     * [nearFraction] of it → NEAR (the "you're close" amber warning); else UNDER.
     */
    fun budgetLevel(monthTotalUsd: Double, capUsd: Double, nearFraction: Double = 0.8): BudgetLevel = when {
        capUsd <= 0.0 -> BudgetLevel.NONE
        monthTotalUsd >= capUsd -> BudgetLevel.OVER
        monthTotalUsd >= capUsd * nearFraction -> BudgetLevel.NEAR
        else -> BudgetLevel.UNDER
    }
}
