package com.pokemon.catchcombo.data

import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.MysqlConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MySQL-based repository for combo data persistence.
 * Supports cross-server synchronization via shared MySQL database.
 *
 * Features:
 * - HikariCP connection pooling for high performance
 * - Thread-safe operations with connection pool
 * - Local cache with periodic sync for performance
 * - Automatic table creation with configurable prefix
 *
 * Cross-server considerations:
 * - Data is written immediately to database for cross-server visibility
 * - Cache is used for read performance, refreshed on access if stale
 * - Uses REPLACE INTO for upsert operations (MySQL specific)
 */
class MySQLRepository(private val config: MysqlConfig) : ComboRepository {
    private var dataSource: HikariDataSource? = null
    private val cache = ConcurrentHashMap<UUID, CachedComboData>()

    // Cache entry with staleness tracking
    private data class CachedComboData(
        val data: ComboData,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        // Cache is considered stale after 5 seconds for cross-server sync
        fun isStale(): Boolean = System.currentTimeMillis() - cachedAt > 5000
    }

    private val tableName: String
        get() = "${config.tablePrefix}combo_data"

    override fun initialize() {
        try {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                username = config.username
                password = config.password
                driverClassName = "com.mysql.cj.jdbc.Driver"

                // Connection pool settings optimized for game servers
                maximumPoolSize = 10
                minimumIdle = 2
                idleTimeout = 300000 // 5 minutes
                connectionTimeout = 10000 // 10 seconds
                maxLifetime = 600000 // 10 minutes

                // Performance optimizations
                addDataSourceProperty("cachePrepStmts", "true")
                addDataSourceProperty("prepStmtCacheSize", "250")
                addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
                addDataSourceProperty("useServerPrepStmts", "true")
                addDataSourceProperty("rewriteBatchedStatements", "true")

                poolName = "CobbleCatchCombo-MySQL"
            }

            dataSource = HikariDataSource(hikariConfig)
            createTables()

            CobbleCatchCombo.LOGGER.info("MySQL database connected: ${config.host}:${config.port}/${config.database}")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to initialize MySQL database", e)
            throw e
        }
    }

    private fun createTables() {
        getConnection()?.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS $tableName (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        chained_species VARCHAR(255),
                        combo_count INT NOT NULL DEFAULT 0,
                        max_combo INT NOT NULL DEFAULT 0,
                        last_updated BIGINT NOT NULL,
                        INDEX idx_last_updated (last_updated)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent())
            }
        }
        CobbleCatchCombo.LOGGER.info("MySQL table '$tableName' ready")
    }

    private fun getConnection(): Connection? {
        return try {
            dataSource?.connection
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to get MySQL connection", e)
            null
        }
    }

    override fun shutdown() {
        try {
            // Flush all cached data to database
            cache.values.forEach { cached ->
                saveToDatabase(cached.data)
            }
            cache.clear()

            dataSource?.close()
            dataSource = null
            CobbleCatchCombo.LOGGER.info("MySQL connection pool closed")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Error during MySQL shutdown", e)
        }
    }

    /**
     * Get combo data for a player.
     * For cross-server support, refreshes from database if cache is stale.
     */
    override fun getComboData(playerUuid: UUID): ComboData? {
        val cached = cache[playerUuid]

        // If cached and not stale, return cached copy
        if (cached != null && !cached.isStale()) {
            return cached.data.copy()
        }

        // Refresh from database for cross-server sync
        val freshData = loadFromDatabase(playerUuid)
        if (freshData != null) {
            cache[playerUuid] = CachedComboData(freshData)
            return freshData.copy()
        }

        return null
    }

    private fun loadFromDatabase(playerUuid: UUID): ComboData? {
        return try {
            getConnection()?.use { conn ->
                conn.prepareStatement("SELECT * FROM $tableName WHERE player_uuid = ?").use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        ComboData(
                            playerUuid = playerUuid,
                            chainedSpecies = rs.getString("chained_species"),
                            comboCount = rs.getInt("combo_count"),
                            maxCombo = rs.getInt("max_combo"),
                            lastUpdated = rs.getLong("last_updated")
                        )
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load combo data from MySQL for $playerUuid", e)
            null
        }
    }

    override fun saveComboData(data: ComboData) {
        // Update cache immediately
        cache[data.playerUuid] = CachedComboData(data.copy())

        // Write to database immediately for cross-server visibility
        saveToDatabase(data)
    }

    private fun saveToDatabase(data: ComboData) {
        try {
            getConnection()?.use { conn ->
                conn.prepareStatement("""
                    REPLACE INTO $tableName
                    (player_uuid, chained_species, combo_count, max_combo, last_updated)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, data.playerUuid.toString())
                    stmt.setString(2, data.chainedSpecies)
                    stmt.setInt(3, data.comboCount)
                    stmt.setInt(4, data.maxCombo)
                    stmt.setLong(5, data.lastUpdated)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to save combo data to MySQL for ${data.playerUuid}", e)
        }
    }

    override fun deleteComboData(playerUuid: UUID) {
        cache.remove(playerUuid)
        try {
            getConnection()?.use { conn ->
                conn.prepareStatement("DELETE FROM $tableName WHERE player_uuid = ?").use { stmt ->
                    stmt.setString(1, playerUuid.toString())
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to delete combo data from MySQL for $playerUuid", e)
        }
    }

    override fun getAllComboData(): List<ComboData> {
        // For cross-server, load fresh from database
        return try {
            val result = mutableListOf<ComboData>()
            getConnection()?.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT * FROM $tableName")
                    while (rs.next()) {
                        val data = ComboData(
                            playerUuid = UUID.fromString(rs.getString("player_uuid")),
                            chainedSpecies = rs.getString("chained_species"),
                            comboCount = rs.getInt("combo_count"),
                            maxCombo = rs.getInt("max_combo"),
                            lastUpdated = rs.getLong("last_updated")
                        )
                        result.add(data)
                        // Update cache while we're at it
                        cache[data.playerUuid] = CachedComboData(data)
                    }
                }
            }
            result
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load all combo data from MySQL", e)
            emptyList()
        }
    }

    /**
     * Force refresh cache from database.
     * Useful after cross-server events or manual database updates.
     */
    fun refreshCache() {
        cache.clear()
        getAllComboData()
        CobbleCatchCombo.LOGGER.debug("MySQL cache refreshed with ${cache.size} entries")
    }
}
