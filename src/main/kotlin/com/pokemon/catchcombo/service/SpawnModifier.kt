package com.pokemon.catchcombo.service

import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Box
import kotlin.random.Random

class SpawnModifier(
    private val config: CatchComboConfig,
    private val comboManager: ComboManager,
    private val bonusCalculator: BonusCalculator
) {
    companion object {
        private const val SEARCH_RADIUS = 64.0
        private const val BASE_SHINY_RATE = 1.0 / 4096.0
    }

    private val statsList = listOf(
        Stats.HP,
        Stats.ATTACK,
        Stats.DEFENCE,
        Stats.SPECIAL_ATTACK,
        Stats.SPECIAL_DEFENCE,
        Stats.SPEED
    )

    /**
     * Modify a Pokemon's properties based on nearby player's combo bonuses.
     * This should be called when a Pokemon spawns.
     */
    fun modifySpawnedPokemon(pokemon: Pokemon, pokemonEntity: PokemonEntity) {
        val world = pokemonEntity.world
        if (world.isClient) return

        // Find the nearest player with a combo
        val nearbyPlayers = world.getEntitiesByClass(
            ServerPlayerEntity::class.java,
            Box.of(pokemonEntity.pos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)
        ) { true }

        if (nearbyPlayers.isEmpty()) return

        // Find the player with the highest relevant combo
        var bestPlayer: ServerPlayerEntity? = null
        var bestBonus: BonusCalculator.BonusResult? = null
        var isChainedSpecies = false

        val speciesId = pokemon.species.resourceIdentifier.toString()

        for (player in nearbyPlayers) {
            val playerUuid = player.uuid
            val chainedSpecies = comboManager.getChainedSpecies(playerUuid)

            // Check if this player is chaining this species or if we should apply global bonus
            val shouldApplyBonus = if (config.shinyBoost.onlyChainedSpecies) {
                chainedSpecies == speciesId
            } else {
                comboManager.hasCombo(playerUuid)
            }

            if (!shouldApplyBonus) continue

            val bonus = comboManager.getCurrentBonus(playerUuid)

            // Prioritize players chaining this specific species
            val playerIsChaining = chainedSpecies == speciesId

            if (bestBonus == null ||
                (playerIsChaining && !isChainedSpecies) ||
                (playerIsChaining == isChainedSpecies && bonus.shinyMultiplier > (bestBonus?.shinyMultiplier ?: 0.0))
            ) {
                bestPlayer = player
                bestBonus = bonus
                isChainedSpecies = playerIsChaining
            }
        }

        if (bestBonus == null || bestPlayer == null) return

        // Apply shiny boost
        if (config.shinyBoost.enabled && bestBonus.shinyMultiplier > 1.0) {
            applyShinyBoost(pokemon, bestBonus.shinyMultiplier)
        }

        // Apply IV boost (only for chained species)
        if (config.ivBoost.enabled && bestBonus.guaranteedPerfectIVs > 0 && isChainedSpecies) {
            applyIvBoost(pokemon, bestBonus.guaranteedPerfectIVs)
        }

        CobbleCatchCombo.LOGGER.debug(
            "Applied combo bonus to ${pokemon.species.name}: " +
            "shiny=${bestBonus.shinyMultiplier}x, IVs=${bestBonus.guaranteedPerfectIVs}V " +
            "(player=${bestPlayer.name.string}, chained=$isChainedSpecies)"
        )
    }

    /**
     * Apply shiny boost by adding additional shiny chance.
     *
     * Math explanation:
     * - Cobblemon already rolled shiny at base rate (1/4096)
     * - For a 2x multiplier, we want total chance = 2/4096
     * - Cobblemon gave us 1/4096, so we add (multiplier - 1) * base_rate
     * - This avoids double-rolling which would give incorrect multiplier values
     *
     * Example: multiplier=2.0
     * - Base rate: 1/4096 ≈ 0.0244%
     * - Additional: (2-1) * 1/4096 = 1/4096 ≈ 0.0244%
     * - Total effective: ~0.0488% = 2/4096 (correct 2x)
     */
    private fun applyShinyBoost(pokemon: Pokemon, multiplier: Double) {
        if (pokemon.shiny) return // Already shiny from Cobblemon's base roll

        // Only apply additional chance beyond the base rate
        // For multiplier=1.0, additionalChance=0 (no extra roll)
        // For multiplier=2.0, additionalChance=1/4096 (one extra base rate)
        val additionalChance = (multiplier - 1.0) * BASE_SHINY_RATE
        if (additionalChance > 0 && Random.nextDouble() < additionalChance) {
            pokemon.shiny = true
            CobbleCatchCombo.LOGGER.debug("Combo bonus made ${pokemon.species.name} shiny! (additional chance: ${String.format("%.4f", additionalChance * 100)}%)")
        }
    }

    /**
     * Apply guaranteed perfect IVs to a Pokemon.
     *
     * Cobblemon API Note:
     * - Pokemon.ivs returns an IVs object that implements operator set ([stat] = value)
     * - Setting ivs[stat] = 31 directly modifies the Pokemon's IV values
     * - This is done after spawn so we're modifying the already-rolled IVs
     *
     * Algorithm:
     * 1. Find all stats that are not already 31 (perfect)
     * 2. Randomly select N of these stats (where N = guaranteedPerfectIVs)
     * 3. Set each selected stat to 31
     *
     * This ensures we don't waste boosts on already-perfect IVs.
     */
    private fun applyIvBoost(pokemon: Pokemon, guaranteedPerfectIVs: Int) {
        if (guaranteedPerfectIVs <= 0) return

        val ivs = pokemon.ivs

        // Get stats that are not already perfect (31)
        val imperfectStats = statsList.filter { stat ->
            (ivs[stat] ?: 0) < 31
        }.toMutableList()

        // Randomly select stats to make perfect
        val statsToBoost = minOf(guaranteedPerfectIVs, imperfectStats.size)

        repeat(statsToBoost) {
            if (imperfectStats.isNotEmpty()) {
                val randomIndex = Random.nextInt(imperfectStats.size)
                val stat = imperfectStats.removeAt(randomIndex)
                ivs[stat] = 31
            }
        }

        CobbleCatchCombo.LOGGER.debug(
            "Applied $statsToBoost perfect IVs to ${pokemon.species.name}"
        )
    }

    /**
     * Calculate effective shiny rate for display purposes
     */
    fun getEffectiveShinyRate(multiplier: Double): String {
        val boostedRate = BASE_SHINY_RATE * multiplier
        val denominator = (1.0 / boostedRate).toInt()
        return "1/$denominator"
    }
}
