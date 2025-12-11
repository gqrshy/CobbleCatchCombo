package com.pokemon.catchcombo.data

import java.util.UUID

data class ComboData(
    val playerUuid: UUID,
    var chainedSpecies: String?,  // e.g., "cobblemon:eevee"
    var comboCount: Int,
    var maxCombo: Int,
    var lastUpdated: Long
) {
    companion object {
        fun empty(playerUuid: UUID): ComboData {
            return ComboData(
                playerUuid = playerUuid,
                chainedSpecies = null,
                comboCount = 0,
                maxCombo = 0,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    fun reset() {
        chainedSpecies = null
        comboCount = 0
        lastUpdated = System.currentTimeMillis()
    }

    /**
     * Increment combo for a species.
     * @param species The species being caught
     * @param forceChainContinue If true, continues the chain regardless of species match
     *                           (used for evolution line matching)
     * @return true if this started a new chain, false if continuing existing chain
     */
    fun incrementCombo(species: String, forceChainContinue: Boolean = false): Boolean {
        val wasNewChain = if (forceChainContinue) {
            // Evolution line match - continue chain but update species
            false
        } else {
            chainedSpecies != species
        }

        if (wasNewChain) {
            // Starting new chain
            chainedSpecies = species
            comboCount = 1
        } else {
            // Continuing chain - update species to current and increment
            chainedSpecies = species
            comboCount++
        }

        if (comboCount > maxCombo) {
            maxCombo = comboCount
        }

        lastUpdated = System.currentTimeMillis()
        return wasNewChain
    }

    fun hasCombo(): Boolean = comboCount > 0 && chainedSpecies != null
}
