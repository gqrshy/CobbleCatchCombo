package com.pokemon.catchcombo.service

import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.config.IvTier
import com.pokemon.catchcombo.config.ShinyTier

class BonusCalculator(private val config: CatchComboConfig) {

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
        val shinyTier = getCurrentShinyTier(comboCount)
        val ivTier = getCurrentIvTier(comboCount)
        val nextShinyTier = getNextShinyTier(comboCount)
        val nextIvTier = getNextIvTier(comboCount)

        return BonusResult(
            shinyMultiplier = if (config.shinyBoost.enabled) shinyTier?.multiplier ?: 1.0 else 1.0,
            guaranteedPerfectIVs = if (config.ivBoost.enabled) ivTier?.guaranteedPerfectIVs ?: 0 else 0,
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
        return baseRate * multiplier
    }

    fun formatShinyRate(multiplier: Double, baseRate: Double = 1.0 / 4096.0): String {
        val boostedRate = baseRate * multiplier
        val denominator = (1.0 / boostedRate).toInt()
        return "1/$denominator"
    }
}
