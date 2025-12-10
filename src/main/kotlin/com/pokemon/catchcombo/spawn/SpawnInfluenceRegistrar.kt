package com.pokemon.catchcombo.spawn

import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawnerFactory
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Registers the CatchComboSpawnInfluence with Cobblemon's spawning system.
 *
 * This uses PlayerSpawnerFactory.influenceBuilders which is the official way
 * to add custom spawn influences in Cobblemon.
 *
 * The influence builder is called for each player when spawns are being
 * processed, allowing us to create player-specific influences that check
 * that player's combo status.
 */
object SpawnInfluenceRegistrar {

    private var registered = false

    /**
     * Register the spawn influence builder with Cobblemon.
     * This should be called once during mod initialization.
     */
    fun register(
        config: CatchComboConfig,
        comboManager: ComboManager,
        bonusCalculator: BonusCalculator
    ) {
        if (registered) {
            CobbleCatchCombo.LOGGER.warn("SpawnInfluenceRegistrar already registered!")
            return
        }

        try {
            // Register our influence builder with Cobblemon's PlayerSpawnerFactory
            // This builder is called for each player when spawns are being processed
            PlayerSpawnerFactory.influenceBuilders.add { spawner ->
                // Get the player from the spawner's cause
                val player = spawner.cause.entity as? ServerPlayerEntity
                if (player != null) {
                    CatchComboSpawnInfluence(
                        player = player,
                        config = config,
                        comboManager = comboManager,
                        bonusCalculator = bonusCalculator
                    )
                } else {
                    // Return a no-op influence if no player
                    NoOpSpawnInfluence
                }
            }

            registered = true
            CobbleCatchCombo.LOGGER.info("Registered CatchCombo spawn influence with Cobblemon")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to register spawn influence with Cobblemon", e)
            CobbleCatchCombo.LOGGER.error("This may be due to Cobblemon API changes. Check Cobblemon version compatibility.")
        }
    }

    /**
     * Check if the spawn influence is registered.
     */
    fun isRegistered(): Boolean = registered
}
