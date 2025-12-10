package com.pokemon.catchcombo.spawn

import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawnerFactory
import com.pokemon.catchcombo.CobbleCatchCombo

/**
 * Registers the CatchComboSpawnInfluence with Cobblemon's spawning system.
 *
 * This uses PlayerSpawnerFactory.influenceBuilders which is the official way
 * to add custom spawn influences in Cobblemon.
 *
 * API Reference (from Cobblemon source):
 * PlayerSpawnerFactory.influenceBuilders is MutableList<(player: ServerPlayer) -> SpawningInfluence?>
 *
 * The influence builder is called for each player when a PlayerSpawner is created,
 * allowing us to create player-specific influences that check that player's combo status.
 *
 * NOTE: The CatchComboSpawnInfluence now fetches config/bonusCalculator at runtime
 * from CobbleCatchCombo, so config reloads are automatically reflected.
 */
object SpawnInfluenceRegistrar {

    private var registered = false

    /**
     * Register the spawn influence builder with Cobblemon.
     * This should be called once during mod initialization.
     */
    fun register() {
        if (registered) {
            CobbleCatchCombo.LOGGER.warn("SpawnInfluenceRegistrar already registered!")
            return
        }

        try {
            // Register our influence builder with Cobblemon's PlayerSpawnerFactory
            // The lambda receives the player directly (not a spawner object)
            // Type: (ServerPlayer) -> SpawningInfluence?
            // NOTE: CatchComboSpawnInfluence fetches config/managers from CobbleCatchCombo
            // at runtime, so we don't need to pass them here.
            PlayerSpawnerFactory.influenceBuilders.add { player ->
                CatchComboSpawnInfluence(player)
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
