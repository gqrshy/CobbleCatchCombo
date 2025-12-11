package com.pokemon.catchcombo.util

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import com.pokemon.catchcombo.CobbleCatchCombo
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility object for species-related formatting functions.
 * This consolidates duplicated code from DisplayManager, CatchComboCommands, and PlaceholderApiIntegration.
 */
object SpeciesUtils {

    // Cache for base species lookups to improve performance
    // Key: species ID (e.g., "cobblemon:pikachu"), Value: base species ID (e.g., "cobblemon:pichu")
    private val baseSpeciesCache = ConcurrentHashMap<String, String>()

    /**
     * Clear the base species cache. Should be called on config reload.
     */
    fun clearCache() {
        baseSpeciesCache.clear()
    }

    /**
     * Get the base species of an evolution line.
     *
     * For example:
     * - "cobblemon:pikachu" -> "cobblemon:pichu"
     * - "cobblemon:raichu" -> "cobblemon:pichu"
     * - "cobblemon:vaporeon" -> "cobblemon:eevee"
     *
     * This traverses up the evolution chain using preEvolution until
     * reaching a species with no pre-evolution (the base).
     *
     * @param speciesId The species identifier (e.g., "cobblemon:pikachu")
     * @return The base species identifier, or the input if no pre-evolution exists
     */
    fun getBaseSpecies(speciesId: String): String {
        // Check cache first
        baseSpeciesCache[speciesId]?.let { return it }

        val baseId = resolveBaseSpecies(speciesId)
        baseSpeciesCache[speciesId] = baseId
        return baseId
    }

    /**
     * Check if two species are in the same evolution line.
     *
     * This compares the base species of both, so:
     * - "pichu" and "pikachu" -> true (both base: pichu)
     * - "pikachu" and "raichu" -> true (both base: pichu)
     * - "eevee" and "vaporeon" -> true (both base: eevee)
     * - "pikachu" and "eevee" -> false (different base species)
     *
     * @param speciesId1 First species identifier
     * @param speciesId2 Second species identifier
     * @return True if both species belong to the same evolution family
     */
    fun areInSameEvolutionLine(speciesId1: String, speciesId2: String): Boolean {
        // Fast path: exact match
        if (speciesId1 == speciesId2) return true

        val base1 = getBaseSpecies(speciesId1)
        val base2 = getBaseSpecies(speciesId2)
        return base1 == base2
    }

    /**
     * Resolve the base species by traversing up the evolution chain.
     */
    private fun resolveBaseSpecies(speciesId: String): String {
        return try {
            val identifier = Identifier.tryParse(speciesId)
            if (identifier == null) {
                CobbleCatchCombo.LOGGER.debug("Could not parse species identifier: $speciesId")
                return speciesId
            }

            val species = PokemonSpecies.getByIdentifier(identifier)
            if (species == null) {
                CobbleCatchCombo.LOGGER.debug("Species not found in registry: $speciesId")
                return speciesId
            }

            // Traverse up the evolution chain to find base species
            val baseSpecies = findBaseSpecies(species, mutableSetOf(species.resourceIdentifier.toString()))
            baseSpecies.resourceIdentifier.toString()
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.debug("Error resolving base species for '$speciesId': ${e.message}")
            speciesId
        }
    }

    /**
     * Recursively find the base species by following preEvolution links.
     *
     * @param species The current species
     * @param visited Set of already visited species IDs to prevent infinite loops
     * @return The base species (first in the evolution line)
     */
    private fun findBaseSpecies(species: Species, visited: MutableSet<String>): Species {
        val preEvolution = species.preEvolution ?: return species

        // Get the species that this one evolves from
        val preEvoSpecies = preEvolution.species
        val preEvoId = preEvoSpecies.resourceIdentifier.toString()

        // Prevent infinite loops (shouldn't happen, but defensive coding)
        if (preEvoId in visited) {
            CobbleCatchCombo.LOGGER.warn("Circular evolution chain detected at: $preEvoId")
            return species
        }
        visited.add(preEvoId)

        // Recursively find the base
        return findBaseSpecies(preEvoSpecies, visited)
    }

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
