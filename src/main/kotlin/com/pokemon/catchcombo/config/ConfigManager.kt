package com.pokemon.catchcombo.config

import com.pokemon.catchcombo.CobbleCatchCombo
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true // Allows JSON5-like comments to be more forgiving
    }

    var config: CatchComboConfig = CatchComboConfig()
        private set

    private val configPath: Path
        get() = CobbleCatchCombo.configDir.resolve("config.json")

    fun loadConfig() {
        try {
            // Ensure config directory exists
            if (!CobbleCatchCombo.configDir.exists()) {
                Files.createDirectories(CobbleCatchCombo.configDir)
            }

            if (configPath.exists()) {
                val content = configPath.readText()
                // Strip JSON5 comments for parsing
                val jsonContent = stripJson5Comments(content)
                config = json.decodeFromString<CatchComboConfig>(jsonContent)
                validateConfig()
                CobbleCatchCombo.LOGGER.info("Config loaded successfully")
            } else {
                // Create default config
                saveConfig()
                CobbleCatchCombo.LOGGER.info("Default config created")
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load config, using defaults", e)
            config = CatchComboConfig()
            saveConfig()
        }
    }

    /**
     * Validate config values and log warnings for any issues.
     * Invalid values are corrected to sensible defaults where possible.
     */
    private fun validateConfig() {
        var hasWarnings = false

        // Validate shiny tiers
        config.shinyBoost.tiers.forEachIndexed { index, tier ->
            if (tier.minCombo < 0) {
                CobbleCatchCombo.LOGGER.warn("shinyBoost.tiers[$index].minCombo is negative (${tier.minCombo}), should be >= 0")
                hasWarnings = true
            }
            if (tier.multiplier < 1.0) {
                CobbleCatchCombo.LOGGER.warn("shinyBoost.tiers[$index].multiplier is < 1.0 (${tier.multiplier}), will be clamped to 1.0")
                hasWarnings = true
            }
        }

        // Validate IV tiers
        config.ivBoost.tiers.forEachIndexed { index, tier ->
            if (tier.minCombo < 0) {
                CobbleCatchCombo.LOGGER.warn("ivBoost.tiers[$index].minCombo is negative (${tier.minCombo}), should be >= 0")
                hasWarnings = true
            }
            if (tier.guaranteedPerfectIVs < 0 || tier.guaranteedPerfectIVs > 6) {
                CobbleCatchCombo.LOGGER.warn("ivBoost.tiers[$index].guaranteedPerfectIVs is out of range (${tier.guaranteedPerfectIVs}), will be clamped to 0-6")
                hasWarnings = true
            }
        }

        // Validate display durations
        if (config.display.bossBar.showDurationSeconds < 0) {
            CobbleCatchCombo.LOGGER.warn("display.bossBar.showDurationSeconds is negative (${config.display.bossBar.showDurationSeconds}), should be >= 0")
            hasWarnings = true
        }
        if (config.display.actionBar.showDurationSeconds < 0) {
            CobbleCatchCombo.LOGGER.warn("display.actionBar.showDurationSeconds is negative (${config.display.actionBar.showDurationSeconds}), should be >= 0")
            hasWarnings = true
        }

        // Validate notification thresholds
        if (config.notifications.onComboBreak.minComboToNotify < 0) {
            CobbleCatchCombo.LOGGER.warn("notifications.onComboBreak.minComboToNotify is negative, should be >= 0")
            hasWarnings = true
        }

        // Validate database config
        val dbType = config.database.type.lowercase()
        if (dbType !in listOf("sqlite", "mysql", "mongodb", "mongo", "mariadb")) {
            CobbleCatchCombo.LOGGER.warn("Unknown database type '${config.database.type}', will fall back to SQLite")
            hasWarnings = true
        }

        // Validate MySQL config if using MySQL
        if (dbType in listOf("mysql", "mariadb")) {
            if (config.database.mysql.port < 1 || config.database.mysql.port > 65535) {
                CobbleCatchCombo.LOGGER.warn("mysql.port is out of valid range (${config.database.mysql.port}), should be 1-65535")
                hasWarnings = true
            }
            if (config.database.mysql.maxPoolSize < 1) {
                CobbleCatchCombo.LOGGER.warn("mysql.maxPoolSize is < 1 (${config.database.mysql.maxPoolSize}), will be clamped to minimum 2")
                hasWarnings = true
            }
        }

        if (hasWarnings) {
            CobbleCatchCombo.LOGGER.warn("Config validation completed with warnings. Some values may be auto-corrected at runtime.")
        }
    }

    fun saveConfig() {
        try {
            if (!CobbleCatchCombo.configDir.exists()) {
                Files.createDirectories(CobbleCatchCombo.configDir)
            }

            val jsonContent = json.encodeToString(CatchComboConfig.serializer(), config)
            // Add header comment
            val contentWithComments = buildConfigWithComments(jsonContent)
            configPath.writeText(contentWithComments)
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to save config", e)
        }
    }

    fun reloadConfig() {
        loadConfig()
    }

    private fun stripJson5Comments(content: String): String {
        val result = StringBuilder()
        var i = 0
        var inString = false
        var escaped = false

        while (i < content.length) {
            val c = content[i]

            if (escaped) {
                result.append(c)
                escaped = false
                i++
                continue
            }

            if (c == '\\' && inString) {
                result.append(c)
                escaped = true
                i++
                continue
            }

            if (c == '"') {
                inString = !inString
                result.append(c)
                i++
                continue
            }

            if (!inString) {
                // Check for single-line comment
                if (c == '/' && i + 1 < content.length && content[i + 1] == '/') {
                    // Skip until end of line
                    while (i < content.length && content[i] != '\n') {
                        i++
                    }
                    continue
                }

                // Check for multi-line comment
                if (c == '/' && i + 1 < content.length && content[i + 1] == '*') {
                    i += 2
                    while (i + 1 < content.length && !(content[i] == '*' && content[i + 1] == '/')) {
                        i++
                    }
                    i += 2
                    continue
                }
            }

            result.append(c)
            i++
        }

        return result.toString()
    }

    private fun buildConfigWithComments(jsonContent: String): String {
        return """
// ===========================================
// CobbleCatchCombo Configuration
// ===========================================
// This file uses JSON format with support for comments.
//
// Database types: "sqlite" (default), "mysql", "mongodb"
// Boss bar colors: YELLOW, RED, GREEN, BLUE, PINK, PURPLE, WHITE
// Boss bar styles: SOLID, SEGMENTED_6, SEGMENTED_10, SEGMENTED_12, SEGMENTED_20
//
// For more information, visit the documentation.
// ===========================================

$jsonContent
""".trimIndent()
    }
}
