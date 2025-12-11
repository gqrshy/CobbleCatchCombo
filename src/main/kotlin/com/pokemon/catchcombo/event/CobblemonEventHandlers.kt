package com.pokemon.catchcombo.event

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.display.DisplayManager
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.ComboManager
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Cobblemon Event Handlers for CobbleCatchCombo
 *
 * Handles:
 * - Pokemon capture events (combo tracking)
 * - Battle flee events (combo reset)
 * - Player death events (combo reset)
 * - Player disconnect events (display cleanup)
 *
 * NOTE: Spawn modification (shiny/IV boosts) is handled separately by
 * CatchComboSpawnInfluence which uses Cobblemon's SpawningInfluence system.
 * This is the correct approach as it modifies spawns BEFORE Pokemon creation.
 *
 * IMPORTANT API NOTES (Cobblemon 1.7.x):
 * ======================================
 * 1. Event Names:
 *    - POKEMON_CAPTURED: Fires when a Pokemon is caught
 *    - BATTLE_FLED: Fires when player flees (check if this exists in 1.7.1)
 *
 * 2. Event Properties:
 *    - PokemonCapturedEvent: .player, .pokemon
 *    - BattleFledEvent: .player (verify this property exists)
 *
 * 3. Priority Enum:
 *    - Located at: com.cobblemon.mod.common.api.Priority
 *    - Values: LOWEST, LOW, NORMAL, HIGH, HIGHEST
 *
 * Reference: https://gitlab.com/cable-mc/cobblemon
 */
object CobblemonEventHandlers {
    private lateinit var comboManager: ComboManager
    private lateinit var displayManager: DisplayManager
    private lateinit var languageManager: LanguageManager

    // Fetch config dynamically to support config reload
    // This ensures event handlers always use the current config values
    private val config: CatchComboConfig
        get() = CobbleCatchCombo.configManager.config

    fun register(
        comboManager: ComboManager,
        displayManager: DisplayManager,
        languageManager: LanguageManager
    ) {
        this.comboManager = comboManager
        this.displayManager = displayManager
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
        // BattleFledEvent properties (from Cobblemon API):
        //   - battle: PokemonBattle - The battle instance
        //   - player: PlayerBattleActor - The player who fled
        // IMPORTANT: Only reset combo for wild battles, not PvP or trainer battles
        try {
            CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event ->
                try {
                    // Get the ServerPlayerEntity from PlayerBattleActor
                    val player = event.player.entity ?: return@subscribe

                    // Use event.battle directly (not event.player.battle)
                    // This is more reliable and matches the API design
                    val battle = event.battle

                    // Check if this is a wild battle by looking for non-player actors
                    // Wild battles have at least one non-player actor (the wild Pokemon)
                    // PvP battles only have PlayerBattleActors
                    val hasWildPokemon = battle.actors.any { actor ->
                        actor !is PlayerBattleActor
                    }

                    // Only reset combo if fleeing from a wild battle
                    if (hasWildPokemon) {
                        handleBattleFled(player)
                    }
                } catch (e: Exception) {
                    CobbleCatchCombo.LOGGER.debug("Error handling battle fled event: ${e.message}")
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.warn("Could not register BATTLE_FLED event - player flee detection disabled")
        }

        // Battle Victory Event (when player defeats wild Pokemon without capturing)
        // This resets the combo if the player wins a wild battle without capturing
        try {
            CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
                try {
                    // Only handle if config says to reset on wild defeat
                    if (!config.combo.resetOnWildDefeat) return@subscribe

                    // Skip if this was a capture - combo is already handled by POKEMON_CAPTURED
                    if (event.wasWildCapture) return@subscribe

                    // Check if this was a wild battle that the player won
                    // Look for player winners and check if there were wild pokemon losers
                    val playerWinners = event.winners.filterIsInstance<PlayerBattleActor>()
                    val hasWildLosers = event.battle.actors.any { actor ->
                        actor !is PlayerBattleActor && event.losers.contains(actor)
                    }

                    if (playerWinners.isNotEmpty() && hasWildLosers) {
                        // Player defeated wild Pokemon without capturing - reset combo
                        for (playerActor in playerWinners) {
                            val player = playerActor.entity ?: continue
                            handleWildDefeat(player)
                        }
                    }
                } catch (e: Exception) {
                    CobbleCatchCombo.LOGGER.debug("Error handling battle victory event: ${e.message}")
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.warn("Could not register BATTLE_VICTORY event - wild defeat detection disabled")
        }

        // NOTE: Spawn modification (shiny/IV boosts) is now handled by
        // CatchComboSpawnInfluence using Cobblemon's SpawningInfluence system.
        // This provides proper timing (before Pokemon creation) and uses
        // the correct API methods (action.props.shiny, IVs.createRandomIVs).
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

    private fun handleWildDefeat(player: ServerPlayerEntity) {
        CobbleCatchCombo.LOGGER.debug("Player ${player.name.string} defeated wild Pokemon without capturing")

        val resetResult = comboManager.resetCombo(player.uuid)
        if (resetResult != null) {
            displayManager.showComboBreakNotification(player, resetResult.previousCombo, resetResult.previousSpecies)
            CobbleCatchCombo.LOGGER.debug(
                "Combo reset for ${player.name.string} due to wild defeat: " +
                "${resetResult.previousSpecies} x${resetResult.previousCombo}"
            )
        }
    }
}
