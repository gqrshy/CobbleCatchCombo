package com.pokemon.catchcombo.service

import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.data.ComboData
import com.pokemon.catchcombo.data.ComboRepository
import com.pokemon.catchcombo.util.SpeciesUtils
import java.util.UUID

/**
 * Manages player catch combo data.
 *
 * Thread Safety:
 * - This class is designed to be used from the main server thread only
 * - Minecraft game events (captures, battles) are processed on the main thread
 * - For cross-server databases (MySQL/MongoDB), data consistency is handled via
 *   immediate database writes and cache staleness checks (5-second window)
 *
 * Note: This class delegates caching to the repository layer
 * to avoid duplicate caching and potential synchronization issues.
 */
class ComboManager {
    private var repository: ComboRepository? = null

    // Fetch config and bonusCalculator dynamically to support config reload
    private val config: CatchComboConfig
        get() = CobbleCatchCombo.configManager.config
    private val bonusCalculator: BonusCalculator
        get() = CobbleCatchCombo.bonusCalculator

    fun initialize(repository: ComboRepository) {
        this.repository = repository
        repository.initialize()

        val entryCount = repository.getAllComboData().size
        CobbleCatchCombo.LOGGER.info("ComboManager initialized with $entryCount entries from repository")
    }

    fun shutdown() {
        repository?.shutdown()
        CobbleCatchCombo.LOGGER.info("ComboManager shutdown complete")
    }

    fun getComboData(playerUuid: UUID): ComboData {
        return repository?.getComboData(playerUuid) ?: ComboData.empty(playerUuid)
    }

    fun getComboCount(playerUuid: UUID): Int {
        return getComboData(playerUuid).comboCount
    }

    fun getChainedSpecies(playerUuid: UUID): String? {
        return getComboData(playerUuid).chainedSpecies
    }

    fun getMaxCombo(playerUuid: UUID): Int {
        return getComboData(playerUuid).maxCombo
    }

    fun hasCombo(playerUuid: UUID): Boolean {
        return getComboData(playerUuid).hasCombo()
    }

    /**
     * Called when a player captures a Pokemon.
     * Returns the result of the capture including whether the chain was broken.
     */
    fun onCapture(playerUuid: UUID, speciesId: String): CaptureResult {
        val data = getComboData(playerUuid)
        val oldCombo = data.comboCount
        val oldSpecies = data.chainedSpecies

        // Check if chain continues based on config
        val chainContinues = if (oldSpecies == null || oldCombo <= 0) {
            // No existing chain, so starting fresh
            true
        } else if (config.combo.treatEvolutionLineAsSameSpecies) {
            // Compare using evolution line - e.g., Pikachu continues a Pichu chain
            SpeciesUtils.areInSameEvolutionLine(oldSpecies, speciesId)
        } else {
            // Exact species match required
            oldSpecies == speciesId
        }

        val chainBroken = oldSpecies != null && !chainContinues && oldCombo > 0

        // Determine if we need to force chain continue (evolution line match but not exact match)
        val forceChainContinue = chainContinues && oldSpecies != null && oldSpecies != speciesId

        val wasNewChain = data.incrementCombo(speciesId, forceChainContinue)

        // Save to database
        repository?.saveComboData(data)

        // Calculate tier changes
        val tierChange = if (chainBroken) {
            // Chain was broken, calculate from 0 to 1
            bonusCalculator.isNewTierReached(0, 1)
        } else {
            bonusCalculator.isNewTierReached(oldCombo, data.comboCount)
        }

        return CaptureResult(
            chainBroken = chainBroken,
            // Ensure previousCombo is never negative (defensive check)
            previousCombo = (if (chainBroken) oldCombo else data.comboCount - 1).coerceAtLeast(0),
            previousSpecies = if (chainBroken) oldSpecies else null,
            newCombo = data.comboCount,
            newSpecies = speciesId,
            tierChange = tierChange,
            currentBonus = bonusCalculator.calculateBonus(data.comboCount)
        )
    }

    /**
     * Reset combo for a player (e.g., when fleeing from battle or dying)
     */
    fun resetCombo(playerUuid: UUID): ResetResult? {
        val data = getComboData(playerUuid)
        if (!data.hasCombo()) {
            return null
        }

        val oldCombo = data.comboCount
        val oldSpecies = data.chainedSpecies

        data.reset()
        repository?.saveComboData(data)

        return ResetResult(
            previousCombo = oldCombo,
            previousSpecies = oldSpecies
        )
    }

    /**
     * Check if the player is currently chaining a specific species
     */
    fun isChaining(playerUuid: UUID, speciesId: String): Boolean {
        return getChainedSpecies(playerUuid) == speciesId
    }

    /**
     * Get the current bonus for a player
     */
    fun getCurrentBonus(playerUuid: UUID): BonusCalculator.BonusResult {
        val comboCount = getComboCount(playerUuid)
        return bonusCalculator.calculateBonus(comboCount)
    }

    data class CaptureResult(
        val chainBroken: Boolean,
        val previousCombo: Int,
        val previousSpecies: String?,
        val newCombo: Int,
        val newSpecies: String,
        val tierChange: BonusCalculator.TierChangeInfo,
        val currentBonus: BonusCalculator.BonusResult
    )

    data class ResetResult(
        val previousCombo: Int,
        val previousSpecies: String?
    )
}
