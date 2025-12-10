package com.pokemon.catchcombo.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import com.pokemon.catchcombo.CobbleCatchCombo
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

object CatchComboCommands {
    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("catchcombo")
                .executes { context -> showCombo(context) }
                .then(
                    literal("reload")
                        .requires { it.hasPermissionLevel(2) }
                        .executes { context -> reloadConfig(context) }
                )
                .then(
                    literal("status")
                        .executes { context -> showCombo(context) }
                )
                .then(
                    literal("reset")
                        .requires { it.hasPermissionLevel(2) }
                        .executes { context -> resetCombo(context) }
                )
        )

        // Alias
        dispatcher.register(
            literal("combo")
                .executes { context -> showCombo(context) }
        )
    }

    private fun showCombo(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player

        if (player == null) {
            source.sendError(Text.literal("This command can only be used by players"))
            return 0
        }

        val comboManager = CobbleCatchCombo.comboManager
        val bonusCalculator = CobbleCatchCombo.bonusCalculator
        val languageManager = CobbleCatchCombo.languageManager

        val comboData = comboManager.getComboData(player.uuid)
        val bonus = comboManager.getCurrentBonus(player.uuid)

        if (!comboData.hasCombo()) {
            source.sendFeedback({ Text.literal("§7You don't have an active catch combo.") }, false)
            return 1
        }

        val speciesName = formatSpeciesName(comboData.chainedSpecies ?: "Unknown")
        val nextMilestone = bonusCalculator.getNextMilestone(comboData.comboCount)

        val message = buildString {
            appendLine("§6§l=== Catch Combo Status ===")
            appendLine("§eSpecies: §f$speciesName")
            appendLine("§eCombo: §f${comboData.comboCount}")
            appendLine("§eMax Combo: §f${comboData.maxCombo}")
            appendLine()
            appendLine("§b§lCurrent Bonuses:")
            appendLine("§7- Shiny Rate: §e${String.format("%.1f", bonus.shinyMultiplier)}x")
            appendLine("§7- Perfect IVs: §b${bonus.guaranteedPerfectIVs}V")

            if (nextMilestone != null) {
                val remaining = nextMilestone - comboData.comboCount
                val nextBonus = bonusCalculator.calculateBonus(nextMilestone)
                appendLine()
                appendLine("§a§lNext Tier: §f$nextMilestone §7($remaining more)")
                appendLine("§7- Shiny Rate: §e${String.format("%.1f", nextBonus.shinyMultiplier)}x")
                appendLine("§7- Perfect IVs: §b${nextBonus.guaranteedPerfectIVs}V")
            }
        }

        source.sendFeedback({ Text.literal(message) }, false)
        return 1
    }

    private fun reloadConfig(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source

        try {
            CobbleCatchCombo.reloadConfig()
            source.sendFeedback({ Text.literal("§aCobbleCatchCombo configuration reloaded successfully!") }, true)
            return 1
        } catch (e: Exception) {
            source.sendError(Text.literal("§cFailed to reload configuration: ${e.message}"))
            CobbleCatchCombo.LOGGER.error("Failed to reload configuration", e)
            return 0
        }
    }

    private fun resetCombo(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player

        if (player == null) {
            source.sendError(Text.literal("This command can only be used by players"))
            return 0
        }

        val comboManager = CobbleCatchCombo.comboManager
        val resetResult = comboManager.resetCombo(player.uuid)

        if (resetResult != null) {
            val speciesName = formatSpeciesName(resetResult.previousSpecies ?: "Unknown")
            source.sendFeedback({
                Text.literal("§cYour ${resetResult.previousCombo} combo of $speciesName has been reset.")
            }, false)
        } else {
            source.sendFeedback({ Text.literal("§7You don't have an active catch combo.") }, false)
        }

        return 1
    }

    private fun formatSpeciesName(speciesId: String): String {
        val name = speciesId.substringAfter(":")
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }
}
