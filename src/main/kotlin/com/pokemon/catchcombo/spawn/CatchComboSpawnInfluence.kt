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
import com.pokemon.catchcombo.util.SpeciesUtils
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
 * Key implementation notes:
 * - Properties are set on SpawnAction.props BEFORE Pokemon creation
 * - Shiny is set via action.props.shiny (not pokemon.shiny)
 * - IVs are set via action.props.ivs using IVs.createRandomIVs()
 * - Species comparison uses resourceIdentifier for consistency with captures
 * - Regional forms (Galarian, Alolan, etc.) are treated as the same base species
 *   because pokemon.species.resourceIdentifier returns the base species ID
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

        // Cache for species identifier lookups to improve performance
        // Key: species name (e.g., "pikachu"), Value: full identifier (e.g., "cobblemon:pikachu")
        private val speciesIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        /**
         * Clear the species ID cache. Should be called on config reload
         * in case species registry changes.
         */
        fun clearCache() {
            speciesIdCache.clear()
        }
    }

    override fun affectAction(action: SpawnAction<*>) {
        // Only handle Pokemon spawns
        if (action !is PokemonSpawnAction) return

        // Get species from spawn detail - this is a string like "pikachu" or "ponyta"
        val speciesName = action.detail.pokemon.species
        if (speciesName.isNullOrBlank()) {
            debug("Spawn has no species defined, skipping")
            return
        }

        // Skip "random" spawns - these are dynamic and shouldn't have combo bonuses
        if (speciesName.equals("random", ignoreCase = true)) {
            debug("Random spawn detected, skipping")
            return
        }

        // Get the proper species identifier from Cobblemon's species registry
        // This ensures we match the same format used when capturing Pokemon
        // (pokemon.species.resourceIdentifier.toString() returns "cobblemon:pikachu")
        val speciesId = getSpeciesIdentifier(speciesName)
        if (speciesId.isBlank()) {
            debug("Could not resolve species identifier for: $speciesName")
            return
        }

        debug("Processing spawn: $speciesName -> $speciesId for player ${player.name.string}")

        // Get player's combo data
        val chainedSpecies = comboManager.getChainedSpecies(player.uuid)
        val comboCount = comboManager.getComboCount(player.uuid)

        if (comboCount <= 0) {
            debug("Player has no combo, skipping")
            return
        }

        // Determine if this species qualifies for bonuses
        // Note: Regional forms share the same base species ID, so they match
        val isChainedSpecies = if (chainedSpecies == null) {
            false
        } else if (config.combo.treatEvolutionLineAsSameSpecies) {
            // Compare using evolution line - e.g., Pikachu spawn gets bonus from Pichu chain
            SpeciesUtils.areInSameEvolutionLine(chainedSpecies, speciesId)
        } else {
            // Exact species match required
            chainedSpecies == speciesId
        }

        // For shiny boost: check config if it applies to all or only chained species
        val shouldApplyShinyBoost = if (config.shinyBoost.onlyChainedSpecies) {
            isChainedSpecies
        } else {
            true // Apply to all Pokemon when player has a combo
        }

        // For IV boost: check config if it applies to all or only chained species
        val shouldApplyIvBoost = if (config.ivBoost.onlyChainedSpecies) {
            isChainedSpecies
        } else {
            true // Apply to all Pokemon when player has a combo
        }

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
     *
     * Results are cached to improve performance as spawn events are frequent.
     */
    private fun getSpeciesIdentifier(speciesName: String): String {
        // Check cache first
        speciesIdCache[speciesName]?.let { return it }

        val identifier = resolveSpeciesIdentifier(speciesName)
        speciesIdCache[speciesName] = identifier
        return identifier
    }

    /**
     * Resolve the species identifier from Cobblemon's registry.
     *
     * The spawn detail's pokemon.species can be:
     * - Just the name: "pikachu"
     * - With namespace: "cobblemon:pikachu"
     *
     * We need to handle both cases and return a consistent format.
     */
    private fun resolveSpeciesIdentifier(speciesName: String): String {
        return try {
            // Check if speciesName already has a namespace (contains ':')
            val normalizedName = if (speciesName.contains(':')) {
                // Already has namespace, try to parse as identifier directly
                val identifier = Identifier.tryParse(speciesName)
                if (identifier != null) {
                    val species = PokemonSpecies.getByIdentifier(identifier)
                    if (species != null) {
                        return species.resourceIdentifier.toString()
                    }
                }
                // If lookup failed, extract the path part for further processing
                speciesName.substringAfter(':')
            } else {
                speciesName
            }

            // Try to look up the species by name in Cobblemon's registry
            val species = PokemonSpecies.getByName(normalizedName)
            if (species != null) {
                return species.resourceIdentifier.toString()
            }

            // If not found by name, try with cobblemon namespace
            val identifier = Identifier.tryParse("cobblemon:$normalizedName")
            if (identifier != null) {
                val speciesByIdentifier = PokemonSpecies.getByIdentifier(identifier)
                if (speciesByIdentifier != null) {
                    return speciesByIdentifier.resourceIdentifier.toString()
                }
            }

            // Fallback: assume cobblemon namespace with the normalized name
            "cobblemon:$normalizedName"
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.debug("Failed to resolve species identifier for '$speciesName': ${e.message}")
            // Fallback: if it has namespace, use it; otherwise add cobblemon namespace
            if (speciesName.contains(':')) speciesName else "cobblemon:$speciesName"
        }
    }
}
