package com.pokemon.catchcombo.data

import com.pokemon.catchcombo.CobbleCatchCombo
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite-based repository for combo data persistence.
 *
 * Thread safety:
 * - Cache operations are thread-safe via ConcurrentHashMap
 * - Database operations are synchronized via ReentrantLock to prevent concurrent SQLite access
 * - ComboData objects are copied when retrieved to prevent concurrent modification
 */
class SQLiteRepository(private val dataDir: Path) : ComboRepository {
    private var connection: Connection? = null
    private val cache = ConcurrentHashMap<UUID, ComboData>()

    // Lock for database operations - SQLite doesn't handle concurrent writes well
    private val dbLock = ReentrantLock()

    override fun initialize() {
        try {
            // Ensure data directory exists
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir)
            }

            val dbPath = dataDir.resolve("cobblecatchcombo.db")
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

            createTables()
            loadAllToCache()

            CobbleCatchCombo.LOGGER.info("SQLite database initialized at $dbPath")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to initialize SQLite database", e)
        }
    }

    private fun createTables() {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS combo_data (
                    player_uuid TEXT PRIMARY KEY,
                    chained_species TEXT,
                    combo_count INTEGER NOT NULL DEFAULT 0,
                    max_combo INTEGER NOT NULL DEFAULT 0,
                    last_updated INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    private fun loadAllToCache() {
        connection?.createStatement()?.use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM combo_data")
            while (rs.next()) {
                val uuid = UUID.fromString(rs.getString("player_uuid"))
                val data = ComboData(
                    playerUuid = uuid,
                    chainedSpecies = rs.getString("chained_species"),
                    comboCount = rs.getInt("combo_count"),
                    maxCombo = rs.getInt("max_combo"),
                    lastUpdated = rs.getLong("last_updated")
                )
                cache[uuid] = data
            }
        }
        CobbleCatchCombo.LOGGER.info("Loaded ${cache.size} combo records from database")
    }

    override fun shutdown() {
        dbLock.withLock {
            // Save all cached data
            cache.values.forEach { saveToDatabase(it) }

            connection?.close()
            connection = null
        }
        CobbleCatchCombo.LOGGER.info("SQLite database connection closed")
    }

    /**
     * Get combo data for a player.
     * Returns a COPY of the cached data to prevent concurrent modification issues.
     * If no data exists, returns null.
     */
    override fun getComboData(playerUuid: UUID): ComboData? {
        return cache[playerUuid]?.copy()
    }

    /**
     * Get combo data for a player, creating new empty data if none exists.
     * This is the preferred method for getting modifiable data.
     */
    fun getOrCreateComboData(playerUuid: UUID): ComboData {
        return cache.computeIfAbsent(playerUuid) { ComboData.empty(playerUuid) }.copy()
    }

    override fun saveComboData(data: ComboData) {
        // Store a copy in cache to prevent external modification
        cache[data.playerUuid] = data.copy()
        // Save to database with lock
        dbLock.withLock {
            saveToDatabase(data)
        }
    }

    private fun saveToDatabase(data: ComboData) {
        try {
            connection?.prepareStatement("""
                INSERT OR REPLACE INTO combo_data
                (player_uuid, chained_species, combo_count, max_combo, last_updated)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent())?.use { stmt ->
                stmt.setString(1, data.playerUuid.toString())
                stmt.setString(2, data.chainedSpecies)
                stmt.setInt(3, data.comboCount)
                stmt.setInt(4, data.maxCombo)
                stmt.setLong(5, data.lastUpdated)
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to save combo data for ${data.playerUuid}", e)
        }
    }

    override fun deleteComboData(playerUuid: UUID) {
        cache.remove(playerUuid)
        dbLock.withLock {
            try {
                connection?.prepareStatement("DELETE FROM combo_data WHERE player_uuid = ?")?.use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    stmt.executeUpdate()
                }
            } catch (e: Exception) {
                CobbleCatchCombo.LOGGER.error("Failed to delete combo data for $playerUuid", e)
            }
        }
    }

    override fun getAllComboData(): List<ComboData> {
        // Return copies to prevent external modification
        return cache.values.map { it.copy() }
    }
}
