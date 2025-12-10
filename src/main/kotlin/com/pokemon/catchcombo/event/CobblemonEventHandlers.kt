package com.pokemon.catchcombo.event

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.battles.BattleFledEvent
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent
import com.cobblemon.mod.common.api.spawning.context.SpawningContext
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.display.DisplayManager
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.ComboManager
import com.pokemon.catchcombo.service.SpawnModifier
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity

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
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL) { event ->
            handlePokemonCaptured(event)
        }

        // Battle Fled Event (player fleeing)
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL) { event ->
            handleBattleFled(event)
        }

        // Pokemon Entity Spawn Event - for applying IV/shiny boosts
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.LOW) { event ->
            handlePokemonSpawn(event.entity)
        }
    }

    private fun registerFabricEvents() {
        // Player death event
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
            if (entity is ServerPlayerEntity && config.combo.resetOnPlayerDeath) {
                handlePlayerDeath(entity)
            }
        }

        // Player disconnect event - cleanup
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            displayManager.removePlayer(handler.player.uuid)
        }
    }

    private fun handlePokemonCaptured(event: PokemonCapturedEvent) {
        val player = event.player
        val pokemon = event.pokemon
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

            // Also notify on tier changes
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

    private fun handleBattleFled(event: BattleFledEvent) {
        if (!config.combo.resetOnPlayerFlee) return

        val player = event.player

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
        spawnModifier.modifySpawnedPokemon(pokemon, pokemonEntity)
    }
}
