package com.pokemon.catchcombo.event

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.display.DisplayManager
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.ComboManager
import com.pokemon.catchcombo.service.SpawnModifier
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Cobblemon Event Handlers for CobbleCatchCombo
 *
 * IMPORTANT API NOTES (Cobblemon 1.7.x):
 * ======================================
 * This file uses Cobblemon's event system. If compilation fails, verify the following:
 *
 * 1. Event Names:
 *    - POKEMON_CAPTURED: Fires when a Pokemon is caught
 *    - POKEMON_ENTITY_SPAWN: Fires when a PokemonEntity spawns
 *    - BATTLE_FLED: Fires when player flees (check if this exists in 1.7.1)
 *
 * 2. Event Properties:
 *    - PokemonCapturedEvent: .player, .pokemon
 *    - PokemonEntitySpawnEvent: .entity (PokemonEntity)
 *    - BattleFledEvent: .player (verify this property exists)
 *
 * 3. Priority Enum:
 *    - Located at: com.cobblemon.mod.common.api.Priority
 *    - Values: LOWEST, LOW, NORMAL, HIGH, HIGHEST
 *
 * 4. Alternative Event Names (if above don't exist):
 *    - POKEMON_ENTITY_SPAWN might be POKEMON_ENTITY_SPAWNED or similar
 *    - Check CobblemonEvents object for available events
 *
 * Reference: https://gitlab.com/cable-mc/cobblemon
 */
object CobblemonEventHandlers {
    private lateinit var comboManager: ComboManager
    private lateinit var displayManager: DisplayManager
    private lateinit var spawnModifier: SpawnModifier
    private lateinit var config: CatchComboConfig
    private lateinit var languageManager: LanguageManager

    fun register(
        comboManager: ComboManager,
        displayManager: DisplayManager,
        spawnModifier: SpawnModifier,
        config: CatchComboConfig,
        languageManager: LanguageManager
    ) {
        this.comboManager = comboManager
        this.displayManager = displayManager
        this.spawnModifier = spawnModifier
        this.config = config
        this.languageManager = languageManager

        registerCobblemonEvents()
        registerFabricEvents()

        CobbleCatchCombo.LOGGER.info("Event handlers registered")
    }

    private fun registerCobblemonEvents() {
        // Pokemon Captured Event
        // This is the main event for tracking catch combos
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL) { event ->
            try {
                handlePokemonCaptured(event.player, event.pokemon)
            } catch (e: Exception) {
                CobbleCatchCombo.LOGGER.error("Error handling Pokemon capture event", e)
            }
        }

        // Battle Fled Event (player fleeing from wild battle)
        // NOTE: In Cobblemon 1.7.0, BattleFledEvent fires when reaching flee distance
        // This might not be the right event for player-initiated fleeing
        // If this causes issues, you may need to use a different approach
        try {
            CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event ->
                try {
                    // The event structure may vary - check if player property exists
                    handleBattleFled(event.player)
                } catch (e: Exception) {
                    CobbleCatchCombo.LOGGER.debug("Error handling battle fled event: ${e.message}")
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.warn("Could not register BATTLE_FLED event - player flee detection disabled")
        }

        // Pokemon Entity Spawn Event - for applying IV/shiny boosts
        // Using LOW priority so other mods can process first
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.LOW) { event ->
            try {
                handlePokemonSpawn(event.entity)
            } catch (e: Exception) {
                CobbleCatchCombo.LOGGER.error("Error handling Pokemon spawn event", e)
            }
        }
    }

    private fun registerFabricEvents() {
        // Player death event
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
            if (entity is ServerPlayerEntity && config.combo.resetOnPlayerDeath) {
                handlePlayerDeath(entity)
            }
        }

        // Player disconnect event - cleanup display resources
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            displayManager.removePlayer(handler.player.uuid)
        }
    }

    private fun handlePokemonCaptured(player: ServerPlayerEntity, pokemon: com.cobblemon.mod.common.pokemon.Pokemon) {
        val speciesId = pokemon.species.resourceIdentifier.toString()

        CobbleCatchCombo.LOGGER.debug("Pokemon captured: $speciesId by ${player.name.string}")

        val result = comboManager.onCapture(player.uuid, speciesId)

        // Show display
        displayManager.showComboDisplay(player, result)

        // Handle chain break notification
        if (result.chainBroken) {
            displayManager.showComboBreakNotification(player, result.previousCombo, result.previousSpecies)
        }

        // Check for milestone notifications
        if (config.notifications.onMilestone.enabled) {
            val milestones = config.notifications.onMilestone.milestones
            if (result.newCombo in milestones) {
                displayManager.showMilestoneNotification(player, result.newCombo, result.currentBonus)
            }

            // Also notify on tier changes that aren't explicit milestones
            if (result.tierChange.hasChanges() && result.newCombo !in milestones) {
                displayManager.showMilestoneNotification(player, result.newCombo, result.currentBonus)
            }
        }

        CobbleCatchCombo.LOGGER.debug(
            "Combo updated for ${player.name.string}: $speciesId x${result.newCombo} " +
            "(broken=${result.chainBroken}, shiny=${result.currentBonus.shinyMultiplier}x, " +
            "iv=${result.currentBonus.guaranteedPerfectIVs}V)"
        )
    }

    private fun handleBattleFled(player: ServerPlayerEntity) {
        if (!config.combo.resetOnPlayerFlee) return

        CobbleCatchCombo.LOGGER.debug("Player ${player.name.string} fled from battle")

        val resetResult = comboManager.resetCombo(player.uuid)
        if (resetResult != null) {
            displayManager.showComboBreakNotification(player, resetResult.previousCombo, resetResult.previousSpecies)
            CobbleCatchCombo.LOGGER.debug(
                "Combo reset for ${player.name.string} due to fleeing: " +
                "${resetResult.previousSpecies} x${resetResult.previousCombo}"
            )
        }
    }

    private fun handlePlayerDeath(player: ServerPlayerEntity) {
        CobbleCatchCombo.LOGGER.debug("Player ${player.name.string} died")

        val resetResult = comboManager.resetCombo(player.uuid)
        if (resetResult != null) {
            displayManager.showComboBreakNotification(player, resetResult.previousCombo, resetResult.previousSpecies)
            CobbleCatchCombo.LOGGER.debug(
                "Combo reset for ${player.name.string} due to death: " +
                "${resetResult.previousSpecies} x${resetResult.previousCombo}"
            )
        }
    }

    private fun handlePokemonSpawn(pokemonEntity: PokemonEntity) {
        val pokemon = pokemonEntity.pokemon

        // Apply spawn modifications (IV boost, shiny boost)
        // This runs after Cobblemon's initial spawn setup
        spawnModifier.modifySpawnedPokemon(pokemon, pokemonEntity)
    }
}
