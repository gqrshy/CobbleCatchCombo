package com.pokemon.catchcombo.spawn

import com.cobblemon.mod.common.api.spawning.detail.SpawnAction
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence

/**
 * A no-op spawn influence that does nothing.
 * Used when there's no valid player to apply combo bonuses for.
 */
object NoOpSpawnInfluence : SpawningInfluence {
    override fun affectAction(action: SpawnAction<*>) {
        // Do nothing
    }
}
