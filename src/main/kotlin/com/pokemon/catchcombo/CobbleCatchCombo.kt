package com.pokemon.catchcombo

import com.pokemon.catchcombo.command.CatchComboCommands
import com.pokemon.catchcombo.config.ConfigManager
import com.pokemon.catchcombo.data.SQLiteRepository
import com.pokemon.catchcombo.display.DisplayManager
import com.pokemon.catchcombo.event.CobblemonEventHandlers
import com.pokemon.catchcombo.integration.PlaceholderApiIntegration
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import com.pokemon.catchcombo.spawn.SpawnInfluenceRegistrar
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Path

object CobbleCatchCombo : ModInitializer {
    const val MOD_ID = "cobblecatchcombo"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    lateinit var configManager: ConfigManager
        private set
    lateinit var languageManager: LanguageManager
        private set
    lateinit var comboManager: ComboManager
        private set
    lateinit var bonusCalculator: BonusCalculator
        private set
    lateinit var displayManager: DisplayManager
        private set

    private var placeholderIntegration: PlaceholderApiIntegration? = null

    val configDir: Path
        get() = FabricLoader.getInstance().configDir.resolve(MOD_ID)

    private var cobblemonLoaded = false

    override fun onInitialize() {
        LOGGER.info("Initializing CobbleCatchCombo...")

        // Check if Cobblemon is loaded
        cobblemonLoaded = FabricLoader.getInstance().isModLoaded("cobblemon")
        if (!cobblemonLoaded) {
            LOGGER.warn("Cobblemon is not loaded! CobbleCatchCombo will be disabled.")
            return
        }

        // Initialize config
        configManager = ConfigManager()
        configManager.loadConfig()

        // Initialize language manager
        languageManager = LanguageManager()
        languageManager.loadLanguages()

        // Initialize bonus calculator
        bonusCalculator = BonusCalculator(configManager.config)

        // Initialize combo manager (will be fully initialized on server start)
        comboManager = ComboManager(bonusCalculator)

        // Initialize display manager
        // NOTE: DisplayManager fetches config/managers from CobbleCatchCombo at runtime
        displayManager = DisplayManager()

        // Register spawn influence with Cobblemon's spawning system
        // This is the correct approach for modifying spawns BEFORE Pokemon creation
        // Reference: Cobblemon Unchained implementation
        // NOTE: SpawnInfluence fetches config/managers from CobbleCatchCombo at runtime
        SpawnInfluenceRegistrar.register()

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            LOGGER.info("Server starting, initializing database...")
            val repository = SQLiteRepository(configDir.resolve("data"))
            comboManager.initialize(repository)
            displayManager.setServer(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            LOGGER.info("Server stopping, saving data...")
            comboManager.shutdown()
            displayManager.shutdown()
        }

        // Register tick event for display manager
        ServerTickEvents.END_SERVER_TICK.register { server ->
            displayManager.tick()
        }

        // Register commands
        CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, environment ->
            CatchComboCommands.register(dispatcher)
        }

        // Register Cobblemon event handlers (capture, flee, death - NOT spawn modification)
        // NOTE: EventHandlers fetch config/managers from CobbleCatchCombo at runtime
        CobblemonEventHandlers.register()

        // Initialize Text Placeholder API integration if available
        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            placeholderIntegration = PlaceholderApiIntegration(comboManager, bonusCalculator, languageManager)
            placeholderIntegration?.register()
            LOGGER.info("Text Placeholder API integration enabled")
        }

        LOGGER.info("CobbleCatchCombo initialized successfully!")
    }

    fun isCobblemonLoaded(): Boolean = cobblemonLoaded

    fun reloadConfig() {
        configManager.reloadConfig()
        languageManager.loadLanguages()
        // Update bonus calculator with new config
        bonusCalculator = BonusCalculator(configManager.config)
        LOGGER.info("Configuration reloaded")
    }
}
