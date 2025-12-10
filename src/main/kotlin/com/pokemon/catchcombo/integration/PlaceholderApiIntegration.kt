package com.pokemon.catchcombo.integration

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import eu.pb4.placeholders.api.PlaceholderContext
import eu.pb4.placeholders.api.PlaceholderResult
import eu.pb4.placeholders.api.Placeholders
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Integration with Text Placeholder API for displaying combo information.
 *
 * NOTE: This class fetches managers from CobbleCatchCombo at runtime
 * to ensure config reloads are properly reflected.
 */
class PlaceholderApiIntegration {
    // Fetch current managers from CobbleCatchCombo to support config reload
    private val comboManager: ComboManager
        get() = CobbleCatchCombo.comboManager
    private val bonusCalculator: BonusCalculator
        get() = CobbleCatchCombo.bonusCalculator
    private val languageManager: LanguageManager
        get() = CobbleCatchCombo.languageManager

    /**
     * Get the base shiny rate from Cobblemon's config.
     * Default is 8192 (1/8192 chance).
     */
    private fun getBaseShinyRate(): Double {
        return try {
            Cobblemon.config.shinyRate.toDouble()
        } catch (e: Exception) {
            8192.0 // Fallback to Cobblemon's default
        }
    }

    fun register() {
        // %cobblecatchcombo:combo_count%
        registerPlaceholder("combo_count") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val count = comboManager.getComboCount(player.uuid)
            PlaceholderResult.value(Text.literal(count.toString()))
        }

        // %cobblecatchcombo:combo_species%
        registerPlaceholder("combo_species") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal(""))
            val species = comboManager.getChainedSpecies(player.uuid)
            val displayName = species?.let { formatSpeciesName(it) } ?: ""
            PlaceholderResult.value(Text.literal(displayName))
        }

        // %cobblecatchcombo:combo_species_id%
        registerPlaceholder("combo_species_id") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal(""))
            val species = comboManager.getChainedSpecies(player.uuid) ?: ""
            PlaceholderResult.value(Text.literal(species))
        }

        // %cobblecatchcombo:shiny_multiplier%
        registerPlaceholder("shiny_multiplier") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("1.0"))
            val bonus = comboManager.getCurrentBonus(player.uuid)
            PlaceholderResult.value(Text.literal(String.format("%.1f", bonus.shinyMultiplier)))
        }

        // %cobblecatchcombo:shiny_rate%
        registerPlaceholder("shiny_rate") { ctx ->
            val baseRate = getBaseShinyRate()
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal(baseRate.toInt().toString()))
            val bonus = comboManager.getCurrentBonus(player.uuid)
            val rate = (baseRate / bonus.shinyMultiplier).toInt()
            PlaceholderResult.value(Text.literal(rate.toString()))
        }

        // %cobblecatchcombo:perfect_ivs%
        registerPlaceholder("perfect_ivs") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val bonus = comboManager.getCurrentBonus(player.uuid)
            PlaceholderResult.value(Text.literal(bonus.guaranteedPerfectIVs.toString()))
        }

        // %cobblecatchcombo:next_tier%
        registerPlaceholder("next_tier") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val comboCount = comboManager.getComboCount(player.uuid)
            val nextMilestone = bonusCalculator.getNextMilestone(comboCount) ?: 0
            PlaceholderResult.value(Text.literal(nextMilestone.toString()))
        }

        // %cobblecatchcombo:next_tier_remaining%
        registerPlaceholder("next_tier_remaining") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val comboCount = comboManager.getComboCount(player.uuid)
            val nextMilestone = bonusCalculator.getNextMilestone(comboCount)
            val remaining = if (nextMilestone != null) nextMilestone - comboCount else 0
            PlaceholderResult.value(Text.literal(remaining.toString()))
        }

        // %cobblecatchcombo:current_tier%
        registerPlaceholder("current_tier") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val comboCount = comboManager.getComboCount(player.uuid)
            val tier = calculateCurrentTier(comboCount)
            PlaceholderResult.value(Text.literal(tier.toString()))
        }

        // %cobblecatchcombo:max_combo%
        registerPlaceholder("max_combo") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("0"))
            val maxCombo = comboManager.getMaxCombo(player.uuid)
            PlaceholderResult.value(Text.literal(maxCombo.toString()))
        }

        // %cobblecatchcombo:has_combo%
        registerPlaceholder("has_combo") { ctx ->
            val player = ctx.player() ?: return@registerPlaceholder PlaceholderResult.value(Text.literal("false"))
            val hasCombo = comboManager.hasCombo(player.uuid)
            PlaceholderResult.value(Text.literal(hasCombo.toString()))
        }

        CobbleCatchCombo.LOGGER.info("Registered ${11} placeholders with Text Placeholder API")
    }

    private fun registerPlaceholder(name: String, handler: (PlaceholderContext) -> PlaceholderResult) {
        val identifier = Identifier.of(CobbleCatchCombo.MOD_ID, name)
        Placeholders.register(identifier) { ctx, _ ->
            handler(ctx)
        }
    }

    /**
     * Format species name using Cobblemon's translated name if available.
     * Falls back to formatted string manipulation if translation not found.
     */
    private fun formatSpeciesName(speciesId: String): String {
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
     * Fallback formatting: Convert "cobblemon:galarian_ponyta" to "Galarian Ponyta"
     */
    private fun fallbackFormatName(speciesId: String): String {
        val name = speciesId.substringAfter(":")
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun calculateCurrentTier(comboCount: Int): Int {
        val config = CobbleCatchCombo.configManager.config
        val shinyTiers = config.shinyBoost.tiers.map { it.minCombo }
        val ivTiers = config.ivBoost.tiers.map { it.minCombo }
        val allTiers = (shinyTiers + ivTiers).distinct().sorted()

        return allTiers.indexOfLast { it <= comboCount } + 1
    }
}
