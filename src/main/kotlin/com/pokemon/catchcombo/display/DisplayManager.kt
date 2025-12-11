package com.pokemon.catchcombo.display

import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.CatchComboConfig
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import com.pokemon.catchcombo.util.SpeciesUtils
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DisplayManager(
    private val languageManager: LanguageManager
) {
    // Fetch config and bonusCalculator dynamically to support config reload
    private val config: CatchComboConfig
        get() = CobbleCatchCombo.configManager.config
    private val bonusCalculator: BonusCalculator
        get() = CobbleCatchCombo.bonusCalculator
    private val playerBossBars = ConcurrentHashMap<UUID, ServerBossBar>()
    // Use system time (millis) instead of world ticks for more reliable timing
    // World ticks can be affected by /time commands or dimension changes
    private val hideScheduledTime = ConcurrentHashMap<UUID, Long>()

    // ActionBar tracking for duration-based display
    // Minecraft's ActionBar auto-fades after ~2 seconds, so we re-send it periodically
    private val activeActionBars = ConcurrentHashMap<UUID, ActionBarState>()

    /**
     * Tracks state for an active ActionBar display.
     * @param message The text to display
     * @param endTime When to stop showing (System.currentTimeMillis())
     * @param nextSendTime When to re-send the ActionBar to prevent fade
     */
    private data class ActionBarState(
        val message: String,
        val endTime: Long,
        var nextSendTime: Long
    )

    companion object {
        // Re-send interval in milliseconds (ActionBar fades after ~2 sec)
        private const val ACTION_BAR_RESEND_INTERVAL = 1500L
    }

    // Server reference for main thread execution
    private var server: MinecraftServer? = null

    fun setServer(server: MinecraftServer) {
        this.server = server
    }

    fun showComboDisplay(player: ServerPlayerEntity, captureResult: ComboManager.CaptureResult) {
        val speciesName = SpeciesUtils.formatSpeciesName(captureResult.newSpecies)

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
            // Guard against division by zero (can happen with misconfigured tiers)
            if (range <= 0) {
                1f
            } else {
                val current = result.newCombo - previousMilestone
                (current.toFloat() / range.toFloat()).coerceIn(0f, 1f)
            }
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

        // Schedule hide using system time (more reliable than world ticks)
        val hideDelayMillis = config.display.bossBar.showDurationSeconds * 1000L
        hideScheduledTime[uuid] = System.currentTimeMillis() + hideDelayMillis
    }

    /**
     * Called every server tick to handle boss bar hiding and ActionBar re-sends.
     * This ensures display operations happen on the main thread.
     */
    fun tick() {
        val currentTime = System.currentTimeMillis()

        // Handle boss bar hiding
        if (hideScheduledTime.isNotEmpty()) {
            val toRemove = mutableListOf<UUID>()

            hideScheduledTime.forEach { (uuid, hideTime) ->
                if (currentTime >= hideTime) {
                    toRemove.add(uuid)
                }
            }

            toRemove.forEach { uuid ->
                hideScheduledTime.remove(uuid)
                hideBossBar(uuid)
            }
        }

        // Handle ActionBar re-sends for duration-based display
        if (activeActionBars.isNotEmpty()) {
            val toRemove = mutableListOf<UUID>()

            activeActionBars.forEach { (uuid, state) ->
                if (currentTime >= state.endTime) {
                    // Duration expired, remove from tracking
                    toRemove.add(uuid)
                } else if (currentTime >= state.nextSendTime) {
                    // Time to re-send to prevent fade
                    server?.playerManager?.getPlayer(uuid)?.let { player ->
                        player.sendMessage(Text.literal(state.message), true)
                    }
                    state.nextSendTime = currentTime + ACTION_BAR_RESEND_INTERVAL
                }
            }

            toRemove.forEach { uuid ->
                activeActionBars.remove(uuid)
            }
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

        // Send immediately
        player.sendMessage(Text.literal(displayText), true)

        // Schedule repeated sends for the configured duration
        val durationMillis = config.display.actionBar.showDurationSeconds * 1000L
        if (durationMillis > 0) {
            val currentTime = System.currentTimeMillis()
            activeActionBars[player.uuid] = ActionBarState(
                message = displayText,
                endTime = currentTime + durationMillis,
                nextSendTime = currentTime + ACTION_BAR_RESEND_INTERVAL
            )
        }
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

        // Guard against division by zero (can happen with misconfigured tiers)
        if (range <= 0) {
            return filled.repeat(barLength)
        }

        val current = currentCombo - previousMilestone
        val filledCount = ((current.toFloat() / range.toFloat()) * barLength).toInt().coerceIn(0, barLength)

        return filled.repeat(filledCount) + empty.repeat(barLength - filledCount)
    }

    private fun getPreviousMilestone(combo: Int): Int {
        val shinyTiers = config.shinyBoost.tiers
        val ivTiers = config.ivBoost.tiers

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
        val speciesName = previousSpecies?.let { SpeciesUtils.formatSpeciesName(it) } ?: "Unknown"

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
        hideScheduledTime.remove(playerUuid)
        activeActionBars.remove(playerUuid)
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
            // Map common config names to Minecraft enum names
            // Config may use "SEGMENTED_X" but Minecraft uses "NOTCHED_X"
            val normalizedStyle = config.display.bossBar.style.uppercase()
                .replace("SEGMENTED_", "NOTCHED_")
            BossBar.Style.valueOf(normalizedStyle)
        } catch (e: Exception) {
            BossBar.Style.PROGRESS
        }
    }

    fun shutdown() {
        playerBossBars.values.forEach { it.clearPlayers() }
        playerBossBars.clear()
        hideScheduledTime.clear()
        activeActionBars.clear()
        server = null
    }
}
