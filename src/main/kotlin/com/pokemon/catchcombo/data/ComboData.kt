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

    fun incrementCombo(species: String): Boolean {
        val wasNewChain = chainedSpecies != species

        if (wasNewChain) {
            // Starting new chain
            chainedSpecies = species
            comboCount = 1
        } else {
            // Continuing chain
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
