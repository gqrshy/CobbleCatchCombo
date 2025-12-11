package com.pokemon.catchcombo.service

import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.config.IvTier
import com.pokemon.catchcombo.config.ShinyTier

/**
 * Calculates shiny and IV bonuses based on catch combo count.
 *
 * Thread-safe: This class is immutable after construction and safe for concurrent use.
 */
class BonusCalculator(private val config: CatchComboConfig) {

    companion object {
        // Maximum valid perfect IVs (HP, Atk, Def, SpA, SpD, Spe)
        const val MAX_PERFECT_IVS = 6
        // Minimum valid multiplier
        const val MIN_MULTIPLIER = 1.0
    }

    data class BonusResult(
        val shinyMultiplier: Double,
        val guaranteedPerfectIVs: Int,
        val currentShinyTier: ShinyTier?,
        val currentIvTier: IvTier?,
        val nextShinyTier: ShinyTier?,
        val nextIvTier: IvTier?
    ) {
        fun hasAnyBonus(): Boolean = shinyMultiplier > 1.0 || guaranteedPerfectIVs > 0
    }

    fun calculateBonus(comboCount: Int): BonusResult {
        // Ensure comboCount is non-negative
        val safeComboCount = comboCount.coerceAtLeast(0)

        val shinyTier = getCurrentShinyTier(safeComboCount)
        val ivTier = getCurrentIvTier(safeComboCount)
        val nextShinyTier = getNextShinyTier(safeComboCount)
        val nextIvTier = getNextIvTier(safeComboCount)

        // Calculate multiplier with validation (minimum 1.0)
        val rawMultiplier = if (config.shinyBoost.enabled) shinyTier?.multiplier ?: 1.0 else 1.0
        val safeMultiplier = rawMultiplier.coerceAtLeast(MIN_MULTIPLIER)

        // Calculate IVs with validation (0-6 range)
        val rawIvs = if (config.ivBoost.enabled) ivTier?.guaranteedPerfectIVs ?: 0 else 0
        val safeIvs = rawIvs.coerceIn(0, MAX_PERFECT_IVS)

        return BonusResult(
            shinyMultiplier = safeMultiplier,
            guaranteedPerfectIVs = safeIvs,
            currentShinyTier = shinyTier,
            currentIvTier = ivTier,
            nextShinyTier = nextShinyTier,
            nextIvTier = nextIvTier
        )
    }

    private fun getCurrentShinyTier(comboCount: Int): ShinyTier? {
        return config.shinyBoost.tiers
            .filter { it.minCombo <= comboCount }
            .maxByOrNull { it.minCombo }
    }

    private fun getCurrentIvTier(comboCount: Int): IvTier? {
        return config.ivBoost.tiers
            .filter { it.minCombo <= comboCount }
            .maxByOrNull { it.minCombo }
    }

    private fun getNextShinyTier(comboCount: Int): ShinyTier? {
        return config.shinyBoost.tiers
            .filter { it.minCombo > comboCount }
            .minByOrNull { it.minCombo }
    }

    private fun getNextIvTier(comboCount: Int): IvTier? {
        return config.ivBoost.tiers
            .filter { it.minCombo > comboCount }
            .minByOrNull { it.minCombo }
    }

    fun getNextMilestone(comboCount: Int): Int? {
        val nextShiny = getNextShinyTier(comboCount)?.minCombo
        val nextIv = getNextIvTier(comboCount)?.minCombo

        return when {
            nextShiny == null && nextIv == null -> null
            nextShiny == null -> nextIv
            nextIv == null -> nextShiny
            else -> minOf(nextShiny, nextIv)
        }
    }

    fun isMilestone(comboCount: Int, milestones: List<Int>): Boolean {
        return comboCount in milestones
    }

    fun isNewTierReached(oldCombo: Int, newCombo: Int): TierChangeInfo {
        val oldBonus = calculateBonus(oldCombo)
        val newBonus = calculateBonus(newCombo)

        val shinyChanged = oldBonus.shinyMultiplier != newBonus.shinyMultiplier
        val ivChanged = oldBonus.guaranteedPerfectIVs != newBonus.guaranteedPerfectIVs

        return TierChangeInfo(
            shinyTierChanged = shinyChanged,
            ivTierChanged = ivChanged,
            newBonus = newBonus
        )
    }

    data class TierChangeInfo(
        val shinyTierChanged: Boolean,
        val ivTierChanged: Boolean,
        val newBonus: BonusResult
    ) {
        fun hasChanges(): Boolean = shinyTierChanged || ivTierChanged
    }

    fun calculateShinyRate(baseRate: Double, multiplier: Double): Double {
        // Ensure multiplier is at least 1.0
        val safeMultiplier = multiplier.coerceAtLeast(MIN_MULTIPLIER)
        return baseRate * safeMultiplier
    }

    /**
     * Format the shiny rate as a human-readable string (e.g., "1/4096").
     *
     * @param multiplier The shiny multiplier (e.g., 2.0 for 2x rate)
     * @param baseRate The base shiny rate as a decimal (default: 1/4096)
     * @return Formatted string like "1/4096" or "1/2048"
     */
    fun formatShinyRate(multiplier: Double, baseRate: Double = 1.0 / 4096.0): String {
        // Ensure multiplier is valid to prevent division by zero
        val safeMultiplier = multiplier.coerceAtLeast(MIN_MULTIPLIER)
        val boostedRate = baseRate * safeMultiplier

        // Prevent division by zero
        if (boostedRate <= 0.0) {
            return "1/∞"
        }

        val denominator = (1.0 / boostedRate).toInt().coerceAtLeast(1)
        return "1/$denominator"
    }
}
