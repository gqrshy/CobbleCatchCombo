package com.pokemon.catchcombo.lang

import com.pokemon.catchcombo.CobbleCatchCombo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LanguageManager {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val translations = mutableMapOf<String, Map<String, String>>()
    private val defaultLocale = "en_us"

    private val langDir: Path
        get() = CobbleCatchCombo.configDir.resolve("lang")

    private val defaultTranslations = mapOf(
        "en_us" to mapOf(
            "_comment" to "CobbleCatchCombo Language File - English",

            "cobblecatchcombo.display.bossbar" to "§6%species% §eCombo: §f%count%§7/%next_tier% §8(%next_bonus%)",
            "cobblecatchcombo.display.bossbar.no_next" to "§6%species% §eCombo: §f%count% §a§lMAX",

            "cobblecatchcombo.display.actionbar" to "§e%species% %progress_bar% §f%count% §7Combo §8| §7Next: §f%next_tier% §7for §e%next_bonus%",
            "cobblecatchcombo.display.actionbar.no_next" to "§e%species% %progress_bar% §f%count% §7Combo §a§lMAX TIER",

            "cobblecatchcombo.progress_bar.filled" to "§a█",
            "cobblecatchcombo.progress_bar.empty" to "§8░",

            "cobblecatchcombo.combo.break" to "§c✖ Your %count% catch combo of %species% was broken!",
            "cobblecatchcombo.combo.new_chain" to "§7Started new combo chain with %species%!",

            "cobblecatchcombo.milestone.reached" to "§a✦ %count% Combo! §7%bonus_description%",
            "cobblecatchcombo.milestone.shiny_boost" to "Shiny rate is now §e%multiplier%x",
            "cobblecatchcombo.milestone.iv_boost" to "§b%iv_count% Perfect IVs §7guaranteed",
            "cobblecatchcombo.milestone.both" to "Shiny §e%multiplier%x §7+ §b%iv_count%V §7guaranteed",

            "cobblecatchcombo.bonus.shiny" to "%multiplier%x Shiny",
            "cobblecatchcombo.bonus.iv" to "%iv_count%V guaranteed",
            "cobblecatchcombo.bonus.both" to "%multiplier%x Shiny + %iv_count%V",
            "cobblecatchcombo.bonus.none" to "No bonus yet"
        ),
        "ja_jp" to mapOf(
            "_comment" to "CobbleCatchCombo 言語ファイル - 日本語",

            "cobblecatchcombo.display.bossbar" to "§6%species% §eコンボ: §f%count%§7/%next_tier% §8(%next_bonus%)",
            "cobblecatchcombo.display.bossbar.no_next" to "§6%species% §eコンボ: §f%count% §a§l最大",

            "cobblecatchcombo.display.actionbar" to "§e%species% %progress_bar% §f%count% §7コンボ §8| §7次: §f%next_tier%で§e%next_bonus%",
            "cobblecatchcombo.display.actionbar.no_next" to "§e%species% %progress_bar% §f%count% §7コンボ §a§l最大ティア",

            "cobblecatchcombo.progress_bar.filled" to "§a█",
            "cobblecatchcombo.progress_bar.empty" to "§8░",

            "cobblecatchcombo.combo.break" to "§c✖ %species%の%count%連鎖コンボが途切れました！",
            "cobblecatchcombo.combo.new_chain" to "§7%species%の新しいコンボチェーンを開始！",

            "cobblecatchcombo.milestone.reached" to "§a✦ %count%コンボ達成！ §7%bonus_description%",
            "cobblecatchcombo.milestone.shiny_boost" to "色違い確率が§e%multiplier%倍§7に！",
            "cobblecatchcombo.milestone.iv_boost" to "§b%iv_count%V確定§7になりました！",
            "cobblecatchcombo.milestone.both" to "色違い§e%multiplier%倍 §7+ §b%iv_count%V確定",

            "cobblecatchcombo.bonus.shiny" to "色違い%multiplier%倍",
            "cobblecatchcombo.bonus.iv" to "%iv_count%V確定",
            "cobblecatchcombo.bonus.both" to "色違い%multiplier%倍 + %iv_count%V",
            "cobblecatchcombo.bonus.none" to "ボーナスなし"
        )
    )

    fun loadLanguages() {
        try {
            if (!langDir.exists()) {
                Files.createDirectories(langDir)
            }

            // Save default language files if they don't exist
            defaultTranslations.forEach { (locale, trans) ->
                val langFile = langDir.resolve("$locale.json")
                if (!langFile.exists()) {
                    saveLanguageFile(locale, trans)
                }
            }

            // Load all language files
            // IMPORTANT: Files.list() returns a Stream that must be closed
            Files.list(langDir).use { stream ->
                stream.forEach { path ->
                    if (path.toString().endsWith(".json")) {
                        val locale = path.fileName.toString().removeSuffix(".json")
                        loadLanguageFile(locale, path)
                    }
                }
            }

            CobbleCatchCombo.LOGGER.info("Loaded ${translations.size} language files")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load language files", e)
            // Use defaults
            translations.putAll(defaultTranslations)
        }
    }

    private fun loadLanguageFile(locale: String, path: Path) {
        try {
            val content = path.readText()
            val jsonObject = json.decodeFromString<JsonObject>(content)
            val trans = mutableMapOf<String, String>()
            jsonObject.forEach { (key, value) ->
                // Only process string primitives, skip nested objects/arrays
                try {
                    if (value.jsonPrimitive.isString) {
                        trans[key] = value.jsonPrimitive.content
                    }
                } catch (e: IllegalArgumentException) {
                    // Skip non-primitive values (nested objects, arrays)
                    CobbleCatchCombo.LOGGER.debug("Skipping non-string value for key '$key' in $locale")
                }
            }
            translations[locale] = trans
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load language file: $path", e)
        }
    }

    private fun saveLanguageFile(locale: String, trans: Map<String, String>) {
        try {
            val langFile = langDir.resolve("$locale.json")
            val jsonContent = buildString {
                appendLine("{")
                trans.entries.forEachIndexed { index, (key, value) ->
                    val escapedValue = value.replace("\\", "\\\\").replace("\"", "\\\"")
                    append("  \"$key\": \"$escapedValue\"")
                    if (index < trans.size - 1) appendLine(",")
                    else appendLine()
                }
                append("}")
            }
            langFile.writeText(jsonContent)
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to save language file: $locale", e)
        }
    }

    fun translate(key: String, locale: String = defaultLocale, placeholders: Map<String, String> = emptyMap()): String {
        val trans = translations[locale] ?: translations[defaultLocale] ?: defaultTranslations[defaultLocale]!!
        var text = trans[key] ?: translations[defaultLocale]?.get(key) ?: key

        placeholders.forEach { (placeholder, value) ->
            text = text.replace("%$placeholder%", value)
        }

        return text
    }

    fun translateText(key: String, locale: String = defaultLocale, placeholders: Map<String, String> = emptyMap()): Text {
        return Text.literal(translate(key, locale, placeholders))
    }

    fun getPlayerLocale(player: ServerPlayerEntity): String {
        // Try to get player's client locale, default to en_us
        return try {
            val clientOptions = player.clientOptions
            val language = clientOptions.language
            if (translations.containsKey(language)) language else defaultLocale
        } catch (e: Exception) {
            defaultLocale
        }
    }

    fun getFilledBar(): String = translate("cobblecatchcombo.progress_bar.filled")
    fun getEmptyBar(): String = translate("cobblecatchcombo.progress_bar.empty")
}
