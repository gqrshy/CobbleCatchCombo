package com.pokemon.catchcombo.data

import com.pokemon.catchcombo.CobbleCatchCombo
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SQLiteRepository(private val dataDir: Path) : ComboRepository {
    private var connection: Connection? = null
    private val cache = ConcurrentHashMap<UUID, ComboData>()

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
        // Save all cached data
        cache.values.forEach { saveToDatabase(it) }

        connection?.close()
        connection = null
        CobbleCatchCombo.LOGGER.info("SQLite database connection closed")
    }

    override fun getComboData(playerUuid: UUID): ComboData? {
        return cache[playerUuid]
    }

    override fun saveComboData(data: ComboData) {
        cache[data.playerUuid] = data
        saveToDatabase(data)
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
        try {
            connection?.prepareStatement("DELETE FROM combo_data WHERE player_uuid = ?")?.use { stmt ->
                stmt.setString(1, playerUuid.toString())
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to delete combo data for $playerUuid", e)
        }
    }

    override fun getAllComboData(): List<ComboData> {
        return cache.values.toList()
    }
}
