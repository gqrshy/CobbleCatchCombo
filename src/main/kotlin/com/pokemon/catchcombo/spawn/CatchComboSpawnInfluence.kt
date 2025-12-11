package com.pokemon.catchcombo.spawn

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnAction
import com.cobblemon.mod.common.api.spawning.detail.SpawnAction
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence
import com.cobblemon.mod.common.pokemon.IVs
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
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
 *
 * NOTE: This class fetches config/bonusCalculator from CobbleCatchCombo at runtime
 * to ensure config reloads are properly reflected without re-registration.
 */
class CatchComboSpawnInfluence(
    private val player: ServerPlayerEntity
) : SpawningInfluence {

    // Fetch current config/managers from CobbleCatchCombo to support config reload
    private val config: CatchComboConfig
        get() = CobbleCatchCombo.configManager.config
    private val comboManager: ComboManager
        get() = CobbleCatchCombo.comboManager
    private val bonusCalculator: BonusCalculator
        get() = CobbleCatchCombo.bonusCalculator

    companion object {
        private const val DEBUG = false
    }

    override fun affectAction(action: SpawnAction<*>) {
        // Only handle Pokemon spawns
        if (action !is PokemonSpawnAction) return

        val speciesName = action.detail.pokemon.species ?: return

        // Get the proper species identifier from Cobblemon's species registry
        // This ensures we match the same format used when capturing Pokemon
        // (pokemon.species.resourceIdentifier.toString() returns "cobblemon:pikachu")
        val speciesId = getSpeciesIdentifier(speciesName)

        debug("Processing spawn: $speciesName -> $speciesId for player ${player.name.string}")

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

        // IMPORTANT: Always set props.shiny to prevent Cobblemon's default shiny roll
        // If we only set it when isShiny=true, Cobblemon would do another roll when isShiny=false,
        // resulting in higher shiny rates than intended (double-roll bug)
        action.props.shiny = isShiny

        if (isShiny) {
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

    /**
     * Get the species identifier in the same format used when capturing Pokemon.
     *
     * The spawn detail's pokemon.species is just the species name (e.g., "pikachu"),
     * but when we capture a Pokemon, we store it using
     * pokemon.species.resourceIdentifier.toString() which returns "cobblemon:pikachu".
     *
     * This method looks up the species in Cobblemon's registry to get the proper
     * identifier, ensuring consistent matching between captures and spawns.
     */
    private fun getSpeciesIdentifier(speciesName: String): String {
        return try {
            // First, try to look up the species by name in Cobblemon's registry
            val species = PokemonSpecies.getByName(speciesName)
            if (species != null) {
                species.resourceIdentifier.toString()
            } else {
                // If not found by name, try with cobblemon namespace
                val identifier = Identifier.tryParse("cobblemon:$speciesName")
                if (identifier != null) {
                    val speciesByIdentifier = PokemonSpecies.getByIdentifier(identifier)
                    speciesByIdentifier?.resourceIdentifier?.toString() ?: "cobblemon:$speciesName"
                } else {
                    "cobblemon:$speciesName"
                }
            }
        } catch (e: Exception) {
            // Fallback to simple concatenation if registry lookup fails
            "cobblemon:$speciesName"
        }
    }
}
