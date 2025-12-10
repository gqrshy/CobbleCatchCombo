package com.pokemon.catchcombo.display

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DisplayManager(
    private val config: CatchComboConfig,
    private val languageManager: LanguageManager,
    private val bonusCalculator: BonusCalculator
) {
    private val playerBossBars = ConcurrentHashMap<UUID, ServerBossBar>()
    private val hideScheduledTicks = ConcurrentHashMap<UUID, Long>()

    // Server reference for main thread execution
    private var server: MinecraftServer? = null

    fun setServer(server: MinecraftServer) {
        this.server = server
    }

    fun showComboDisplay(player: ServerPlayerEntity, captureResult: ComboManager.CaptureResult) {
        val speciesName = formatSpeciesName(captureResult.newSpecies)

        if (config.display.bossBar.enabled) {
            showBossBar(player, captureResult, speciesName)
        }

        if (config.display.actionBar.enabled) {
            showActionBar(player, captureResult, speciesName)
        }
    }

    private fun showBossBar(player: ServerPlayerEntity, result: ComboManager.CaptureResult, speciesName: String) {
        val uuid = player.uuid
        val locale = languageManager.getPlayerLocale(player)

        // Get or create boss bar
        val bossBar = playerBossBars.getOrPut(uuid) {
            ServerBossBar(Text.empty(), getBossBarColor(), getBossBarStyle())
        }

        // Calculate progress to next tier
        val nextMilestone = bonusCalculator.getNextMilestone(result.newCombo)
        val progress = if (nextMilestone != null && config.display.bossBar.showProgressToNextTier) {
            val previousMilestone = getPreviousMilestone(result.newCombo)
            val range = nextMilestone - previousMilestone
            val current = result.newCombo - previousMilestone
            (current.toFloat() / range.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }

        // Build display text
        val placeholders = buildPlaceholders(result, speciesName, locale)
        val textKey = if (nextMilestone != null) {
            "cobblecatchcombo.display.bossbar"
        } else {
            "cobblecatchcombo.display.bossbar.no_next"
        }
        val displayText = languageManager.translate(textKey, locale, placeholders)

        // Update boss bar
        bossBar.name = Text.literal(displayText)
        bossBar.percent = progress
        bossBar.color = getBossBarColor()
        bossBar.style = getBossBarStyle()

        // Add player to boss bar if not already added
        if (player !in bossBar.players) {
            bossBar.addPlayer(player)
        }

        // Schedule hide using server ticks (20 ticks = 1 second)
        val hideDelayTicks = config.display.bossBar.showDurationSeconds * 20L
        val currentTick = server?.overworld?.time ?: 0L
        hideScheduledTicks[uuid] = currentTick + hideDelayTicks
    }

    /**
     * Called every server tick to handle boss bar hiding.
     * This ensures boss bar operations happen on the main thread.
     */
    fun tick() {
        val currentTick = server?.overworld?.time ?: return

        val toRemove = mutableListOf<UUID>()
        hideScheduledTicks.forEach { (uuid, hideTick) ->
            if (currentTick >= hideTick) {
                toRemove.add(uuid)
            }
        }

        toRemove.forEach { uuid ->
            hideScheduledTicks.remove(uuid)
            hideBossBar(uuid)
        }
    }

    private fun showActionBar(player: ServerPlayerEntity, result: ComboManager.CaptureResult, speciesName: String) {
        val locale = languageManager.getPlayerLocale(player)
        val placeholders = buildPlaceholders(result, speciesName, locale)

        val nextMilestone = bonusCalculator.getNextMilestone(result.newCombo)
        val textKey = if (nextMilestone != null) {
            "cobblecatchcombo.display.actionbar"
        } else {
            "cobblecatchcombo.display.actionbar.no_next"
        }

        val displayText = languageManager.translate(textKey, locale, placeholders)
        player.sendMessage(Text.literal(displayText), true)
    }

    private fun buildPlaceholders(result: ComboManager.CaptureResult, speciesName: String, locale: String): Map<String, String> {
        val nextMilestone = bonusCalculator.getNextMilestone(result.newCombo)
        val bonus = result.currentBonus

        val nextBonusText = if (nextMilestone != null) {
            val nextBonus = bonusCalculator.calculateBonus(nextMilestone)
            formatBonusDescription(nextBonus, locale)
        } else {
            "MAX"
        }

        // Build progress bar
        val progressBar = buildProgressBar(result.newCombo, nextMilestone, locale)

        return mapOf(
            "species" to speciesName,
            "count" to result.newCombo.toString(),
            "next_tier" to (nextMilestone?.toString() ?: "MAX"),
            "next_bonus" to nextBonusText,
            "multiplier" to String.format("%.1f", bonus.shinyMultiplier),
            "iv_count" to bonus.guaranteedPerfectIVs.toString(),
            "progress_bar" to progressBar
        )
    }

    private fun buildProgressBar(currentCombo: Int, nextMilestone: Int?, locale: String): String {
        val barLength = 10
        val filled = languageManager.getFilledBar()
        val empty = languageManager.getEmptyBar()

        if (nextMilestone == null) {
            return filled.repeat(barLength)
        }

        val previousMilestone = getPreviousMilestone(currentCombo)
        val range = nextMilestone - previousMilestone
        val current = currentCombo - previousMilestone
        val filledCount = ((current.toFloat() / range.toFloat()) * barLength).toInt().coerceIn(0, barLength)

        return filled.repeat(filledCount) + empty.repeat(barLength - filledCount)
    }

    private fun getPreviousMilestone(combo: Int): Int {
        val shinyTiers = CobbleCatchCombo.configManager.config.shinyBoost.tiers
        val ivTiers = CobbleCatchCombo.configManager.config.ivBoost.tiers

        val allMilestones = (shinyTiers.map { it.minCombo } + ivTiers.map { it.minCombo })
            .distinct()
            .sorted()

        return allMilestones.filter { it <= combo }.maxOrNull() ?: 0
    }

    fun formatBonusDescription(bonus: BonusCalculator.BonusResult, locale: String): String {
        val hasShiny = bonus.shinyMultiplier > 1.0
        val hasIv = bonus.guaranteedPerfectIVs > 0

        val key = when {
            hasShiny && hasIv -> "cobblecatchcombo.bonus.both"
            hasShiny -> "cobblecatchcombo.bonus.shiny"
            hasIv -> "cobblecatchcombo.bonus.iv"
            else -> "cobblecatchcombo.bonus.none"
        }

        return languageManager.translate(key, locale, mapOf(
            "multiplier" to String.format("%.1f", bonus.shinyMultiplier),
            "iv_count" to bonus.guaranteedPerfectIVs.toString()
        ))
    }

    fun showComboBreakNotification(player: ServerPlayerEntity, previousCombo: Int, previousSpecies: String?) {
        if (!config.notifications.onComboBreak.enabled || !config.notifications.onComboBreak.sendChat) return
        if (previousCombo < config.notifications.onComboBreak.minComboToNotify) return

        val locale = languageManager.getPlayerLocale(player)
        val speciesName = previousSpecies?.let { formatSpeciesName(it) } ?: "Unknown"

        val message = languageManager.translate("cobblecatchcombo.combo.break", locale, mapOf(
            "count" to previousCombo.toString(),
            "species" to speciesName
        ))

        player.sendMessage(Text.literal(message), false)
    }

    fun showMilestoneNotification(player: ServerPlayerEntity, comboCount: Int, bonus: BonusCalculator.BonusResult) {
        if (!config.notifications.onMilestone.enabled || !config.notifications.onMilestone.sendChat) return

        val locale = languageManager.getPlayerLocale(player)
        val bonusDescription = formatBonusDescription(bonus, locale)

        val message = languageManager.translate("cobblecatchcombo.milestone.reached", locale, mapOf(
            "count" to comboCount.toString(),
            "bonus_description" to bonusDescription
        ))

        player.sendMessage(Text.literal(message), false)
    }

    private fun hideBossBar(playerUuid: UUID) {
        playerBossBars.remove(playerUuid)?.let { bossBar ->
            bossBar.clearPlayers()
        }
    }

    fun removePlayer(playerUuid: UUID) {
        hideScheduledTicks.remove(playerUuid)
        hideBossBar(playerUuid)
    }

    private fun getBossBarColor(): BossBar.Color {
        return try {
            BossBar.Color.valueOf(config.display.bossBar.color.uppercase())
        } catch (e: Exception) {
            BossBar.Color.YELLOW
        }
    }

    private fun getBossBarStyle(): BossBar.Style {
        return try {
            BossBar.Style.valueOf(config.display.bossBar.style.uppercase())
        } catch (e: Exception) {
            BossBar.Style.PROGRESS
        }
    }

    private fun formatSpeciesName(speciesId: String): String {
        // Try to get the translated species name from Cobblemon
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

    private fun fallbackFormatName(speciesId: String): String {
        // Fallback: Convert "cobblemon:galarian_ponyta" to "Galarian Ponyta"
        val name = speciesId.substringAfter(":")
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    fun shutdown() {
        playerBossBars.values.forEach { it.clearPlayers() }
        playerBossBars.clear()
        hideScheduledTicks.clear()
        server = null
    }

    companion object {
        private lateinit var instance: DisplayManager

        // For reference in other classes
        object CobbleCatchCombo {
            val configManager get() = com.pokemon.catchcombo.CobbleCatchCombo.configManager
        }
    }
}
