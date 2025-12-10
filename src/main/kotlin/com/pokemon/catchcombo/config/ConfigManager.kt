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
