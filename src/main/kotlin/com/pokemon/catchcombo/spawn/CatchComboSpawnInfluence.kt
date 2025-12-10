package com.pokemon.catchcombo.spawn

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnAction
import com.cobblemon.mod.common.api.spawning.detail.SpawnAction
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence
import com.cobblemon.mod.common.pokemon.IVs
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import net.minecraft.server.network.ServerPlayerEntity
import kotlin.random.Random

/**
 * Spawning influence that modifies Pokemon spawns based on player catch combos.
 *
 * This uses Cobblemon's SpawningInfluence system which is the correct approach
 * for modifying spawn properties BEFORE the Pokemon is created.
 *
 * Reference: Cobblemon Unchained implementation
 * https://github.com/timinc-cobble/cobblemon-unchained-1.5-fabric
 *
 * Key differences from POKEMON_ENTITY_SPAWN event approach:
 * - Properties are set on SpawnAction.props BEFORE Pokemon creation
 * - Shiny is set via action.props.shiny (not pokemon.shiny)
 * - IVs are set via action.props.ivs using IVs.createRandomIVs()
 * - This ensures proper client sync and internal state consistency
 */
class CatchComboSpawnInfluence(
    private val player: ServerPlayerEntity,
    private val config: CatchComboConfig,
    private val comboManager: ComboManager,
    private val bonusCalculator: BonusCalculator
) : SpawningInfluence {

    companion object {
        private const val DEBUG = false
    }

    override fun affectAction(action: SpawnAction<*>) {
        // Only handle Pokemon spawns
        if (action !is PokemonSpawnAction) return

        val speciesName = action.detail.pokemon.species ?: return
        val speciesId = "cobblemon:$speciesName"

        debug("Processing spawn: $speciesName for player ${player.name.string}")

        // Get player's combo data
        val chainedSpecies = comboManager.getChainedSpecies(player.uuid)
        val comboCount = comboManager.getComboCount(player.uuid)

        if (comboCount <= 0) {
            debug("Player has no combo, skipping")
            return
        }

        // Determine if this species qualifies for bonuses
        val isChainedSpecies = chainedSpecies == speciesId

        // For shiny boost: check config if it applies to all or only chained species
        val shouldApplyShinyBoost = if (config.shinyBoost.onlyChainedSpecies) {
            isChainedSpecies
        } else {
            true // Apply to all Pokemon when player has a combo
        }

        // IV boost always requires chained species
        val shouldApplyIvBoost = isChainedSpecies

        if (!shouldApplyShinyBoost && !shouldApplyIvBoost) {
            debug("No bonuses apply for $speciesName (chained: $chainedSpecies)")
            return
        }

        // Calculate bonuses
        val bonus = bonusCalculator.calculateBonus(comboCount)

        debug("Combo: $comboCount, Shiny: ${bonus.shinyMultiplier}x, IVs: ${bonus.guaranteedPerfectIVs}V")

        // Apply shiny boost
        if (config.shinyBoost.enabled && shouldApplyShinyBoost && bonus.shinyMultiplier > 1.0) {
            applyShinyBoost(action, bonus.shinyMultiplier)
        }

        // Apply IV boost
        if (config.ivBoost.enabled && shouldApplyIvBoost && bonus.guaranteedPerfectIVs > 0) {
            applyIvBoost(action, bonus.guaranteedPerfectIVs)
        }
    }

    /**
     * Apply shiny boost by setting action.props.shiny BEFORE Pokemon creation.
     *
     * Math explanation:
     * - Cobblemon's base shiny rate comes from Cobblemon.config.shinyRate (default: 8192)
     * - Base chance = 1 / shinyRate (e.g., 1/8192)
     * - Boosted chance = multiplier / shinyRate (e.g., 2/8192 for 2x)
     * - We use floating point to preserve fractional multipliers (1.5x, 2.5x, etc.)
     *
     * This approach is correct because:
     * 1. We set props.shiny BEFORE the Pokemon is created
     * 2. Cobblemon will use our value instead of rolling its own
     * 3. No double-rolling issues
     */
    private fun applyShinyBoost(action: PokemonSpawnAction, multiplier: Double) {
        // Don't override if shiny is already set
        if (action.props.shiny != null) {
            debug("Shiny already set, skipping")
            return
        }

        // Get Cobblemon's shiny rate (default is 8192)
        val shinyRate = try {
            Cobblemon.config.shinyRate.toDouble()
        } catch (e: Exception) {
            8192.0 // Fallback to default
        }

        // Calculate boosted probability
        // For multiplier=2.0, we get 2/8192 chance
        // For multiplier=1.5, we get 1.5/8192 chance
        val boostedProbability = multiplier / shinyRate

        // Roll for shiny using floating point comparison
        val roll = Random.nextDouble()
        val isShiny = roll < boostedProbability

        if (isShiny) {
            action.props.shiny = true
            debug("SHINY! Roll: ${String.format("%.6f", roll)} < ${String.format("%.6f", boostedProbability)}")
            CobbleCatchCombo.LOGGER.debug(
                "Catch combo made spawn shiny! Player: ${player.name.string}, " +
                "Multiplier: ${multiplier}x, Probability: ${String.format("%.4f", boostedProbability * 100)}%"
            )
        } else {
            debug("Not shiny. Roll: ${String.format("%.6f", roll)} >= ${String.format("%.6f", boostedProbability)}")
        }
    }

    /**
     * Apply IV boost by setting action.props.ivs BEFORE Pokemon creation.
     *
     * Uses Cobblemon's IVs.createRandomIVs() which properly creates
     * an IVs object with the specified number of guaranteed perfect (31) IVs.
     */
    private fun applyIvBoost(action: PokemonSpawnAction, guaranteedPerfectIVs: Int) {
        // Don't override if IVs are already set
        if (action.props.ivs != null) {
            debug("IVs already set, skipping")
            return
        }

        // Use Cobblemon's API to create IVs with guaranteed perfect stats
        val ivs = IVs.createRandomIVs(guaranteedPerfectIVs)
        action.props.ivs = ivs

        debug("Set $guaranteedPerfectIVs perfect IVs")
        CobbleCatchCombo.LOGGER.debug(
            "Catch combo set $guaranteedPerfectIVs perfect IVs for spawn. Player: ${player.name.string}"
        )
    }

    private fun debug(msg: String) {
        if (DEBUG) {
            println("[CatchCombo] $msg")
        }
    }
}
