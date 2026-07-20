package com.fadghost.notesapp.data.ai

import com.fadghost.notesapp.data.ai.cost.CostAccumulator
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.db.dao.AiCostDao
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monthly AI budget cap (IDEAS #26). One gate shared by every OpenRouter path —
 * text (AiRepository), Ask, and speech-to-text — so a reached cap stops spend
 * everywhere at once, BEFORE a request leaves the device. A cap of 0 means "no
 * cap" and the gate is a no-op. Recorded costs come from OpenRouter's own
 * post-hoc usage rows, so the check is against real spend, not estimates.
 */
@Singleton
class AiBudgetGate @Inject constructor(
    private val prefs: AiPreferences,
    private val costDao: AiCostDao
) {

    /** Throws [OpenRouterError.BudgetReached] when the current month's spend >= cap. */
    suspend fun ensureWithinBudget(now: Long = System.currentTimeMillis()) {
        val cap = prefs.monthlyBudgetUsd.first()
        if (cap <= 0.0) return
        val total = costDao.monthTotal(CostAccumulator.startOfMonth(now, ZoneId.systemDefault()))
        if (CostAccumulator.budgetLevel(total, cap) == CostAccumulator.BudgetLevel.OVER) {
            throw OpenRouterError.BudgetReached(cap)
        }
    }
}
