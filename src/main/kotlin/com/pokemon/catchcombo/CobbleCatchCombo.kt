package com.pokemon.catchcombo

import com.pokemon.catchcombo.command.CatchComboCommands
import com.pokemon.catchcombo.config.ConfigManager
import com.pokemon.catchcombo.data.RepositoryFactory
import com.pokemon.catchcombo.display.DisplayManager
import com.pokemon.catchcombo.event.CobblemonEventHandlers
import com.pokemon.catchcombo.integration.PlaceholderApiIntegration
import com.pokemon.catchcombo.lang.LanguageManager
import com.pokemon.catchcombo.service.BonusCalculator
import com.pokemon.catchcombo.service.ComboManager
import com.pokemon.catchcombo.spawn.CatchComboSpawnInfluence
import com.pokemon.catchcombo.spawn.SpawnInfluenceRegistrar
import com.pokemon.catchcombo.util.SpeciesUtils
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

        // Save initial database type for reload warnings
        saveInitialDbType()

        // Initialize language manager
        languageManager = LanguageManager()
        languageManager.loadLanguages()

        // Initialize bonus calculator
        bonusCalculator = BonusCalculator(configManager.config)

        // Initialize combo manager (will be fully initialized on server start)
        // Note: bonusCalculator is fetched dynamically to support reload
        comboManager = ComboManager()

        // Initialize display manager
        // Note: config and bonusCalculator are fetched dynamically to support reload
        displayManager = DisplayManager(languageManager)

        // Register spawn influence with Cobblemon's spawning system
        // This is the correct approach for modifying spawns BEFORE Pokemon creation
        // Reference: Cobblemon Unchained implementation
        // NOTE: SpawnInfluence fetches config/managers from CobbleCatchCombo at runtime
        SpawnInfluenceRegistrar.register()

        // Register server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            LOGGER.info("Server starting, initializing database...")

            // Create repository based on config (supports SQLite, MySQL, MongoDB)
            val dbConfig = configManager.config.database
            val repository = RepositoryFactory.createRepository(dbConfig, configDir.resolve("data"))

            // Log cross-server status
            if (RepositoryFactory.isCrossServerEnabled(dbConfig)) {
                LOGGER.info("Cross-server synchronization ENABLED via ${RepositoryFactory.getDatabaseDescription(dbConfig)}")
            } else {
                LOGGER.info("Single-server mode (SQLite)")
            }

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
        // Note: config is fetched dynamically in CobblemonEventHandlers to support reload
        CobblemonEventHandlers.register(comboManager, displayManager, languageManager)

        // Initialize Text Placeholder API integration if available
        // Note: bonusCalculator is fetched dynamically in PlaceholderApiIntegration to support reload
        if (FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            placeholderIntegration = PlaceholderApiIntegration(comboManager, languageManager)
            placeholderIntegration?.register()
            LOGGER.info("Text Placeholder API integration enabled")
        }

        LOGGER.info("CobbleCatchCombo initialized successfully!")
    }

    fun isCobblemonLoaded(): Boolean = cobblemonLoaded

    // Track initial database config for reload warnings
    // This is set in onInitialize after first config load
    private var initialDbType: String? = null

    /**
     * Save the initial database type after first config load.
     * Must be called from onInitialize after configManager.loadConfig().
     */
    private fun saveInitialDbType() {
        initialDbType = configManager.config.database.type
    }

    fun reloadConfig() {
        // Ensure initial db type is saved (for backward compatibility if called before onInitialize completes)
        if (initialDbType == null) {
            saveInitialDbType()
        }

        configManager.reloadConfig()
        languageManager.loadLanguages()

        // Warn if database type was changed (requires restart to take effect)
        val newDbType = configManager.config.database.type
        if (initialDbType != newDbType) {
            LOGGER.warn("Database type changed from '$initialDbType' to '$newDbType'. Server restart required for this change to take effect.")
        }

        // Update bonus calculator with new config
        bonusCalculator = BonusCalculator(configManager.config)
        // Clear caches in case species registry changed
        CatchComboSpawnInfluence.clearCache()
        SpeciesUtils.clearCache()
        LOGGER.info("Configuration reloaded")
    }
}
