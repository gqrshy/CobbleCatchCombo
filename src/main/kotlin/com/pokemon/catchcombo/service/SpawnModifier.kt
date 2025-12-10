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

    private fun applyShinyBoost(pokemon: Pokemon, multiplier: Double) {
        if (pokemon.shiny) return // Already shiny

        val boostedRate = BASE_SHINY_RATE * multiplier
        if (Random.nextDouble() < boostedRate) {
            pokemon.shiny = true
            CobbleCatchCombo.LOGGER.debug("Combo bonus made ${pokemon.species.name} shiny!")
        }
    }

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
