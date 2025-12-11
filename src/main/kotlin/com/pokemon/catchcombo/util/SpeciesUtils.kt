package com.pokemon.catchcombo.util

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import net.minecraft.util.Identifier

/**
 * Utility object for species-related formatting functions.
 * This consolidates duplicated code from DisplayManager, CatchComboCommands, and PlaceholderApiIntegration.
 */
object SpeciesUtils {

    /**
     * Format a species ID to a display-friendly name.
     * First tries to get the translated name from Cobblemon's species registry,
     * then falls back to formatting the ID string.
     *
     * @param speciesId The species identifier (e.g., "cobblemon:pikachu")
     * @return The formatted display name (e.g., "Pikachu" or "Galarian Ponyta")
     */
    fun formatSpeciesName(speciesId: String): String {
        return try {
            val identifier = Identifier.tryParse(speciesId)
            if (identifier != null) {
                val species = PokemonSpecies.getByIdentifier(identifier)
                species?.translatedName?.string ?: fallbackFormatName(speciesId)
            } else {
                fallbackFormatName(speciesId)
            }
        } catch (e: Exception) {
            fallbackFormatName(speciesId)
        }
    }

    /**
     * Fallback formatting when Cobblemon registry lookup fails.
     * Converts "cobblemon:galarian_ponyta" to "Galarian Ponyta".
     *
     * @param speciesId The species identifier
     * @return The formatted name with underscores replaced and words capitalized
     */
    private fun fallbackFormatName(speciesId: String): String {
        val name = speciesId.substringAfter(":")
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }
}
