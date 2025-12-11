package com.pokemon.catchcombo.data

import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.DatabaseConfig
import java.nio.file.Path

/**
 * Factory for creating the appropriate ComboRepository based on configuration.
 *
 * Supported database types:
 * - sqlite: Local SQLite database (default, single server)
 * - mysql: MySQL/MariaDB database (cross-server support)
 * - mongodb: MongoDB database (cross-server support)
 *
 * Cross-server considerations:
 * - MySQL and MongoDB support automatic data synchronization across servers
 * - SQLite is only suitable for single-server setups
 */
object RepositoryFactory {

    /**
     * Supported database types
     */
    enum class DatabaseType {
        SQLITE,
        MYSQL,
        MONGODB;

        companion object {
            fun fromString(type: String): DatabaseType {
                return when (type.lowercase().trim()) {
                    "sqlite" -> SQLITE
                    "mysql", "mariadb" -> MYSQL
                    "mongodb", "mongo" -> MONGODB
                    else -> {
                        CobbleCatchCombo.LOGGER.warn("Unknown database type '$type', falling back to SQLite")
                        SQLITE
                    }
                }
            }
        }
    }

    /**
     * Create a repository based on the database configuration.
     *
     * @param config The database configuration
     * @param dataDir The data directory for SQLite (ignored for other types)
     * @return The appropriate ComboRepository implementation
     * @throws IllegalArgumentException if the database type is invalid
     */
    fun createRepository(config: DatabaseConfig, dataDir: Path): ComboRepository {
        val dbType = DatabaseType.fromString(config.type)

        CobbleCatchCombo.LOGGER.info("Creating database repository: ${dbType.name}")

        return when (dbType) {
            DatabaseType.SQLITE -> {
                CobbleCatchCombo.LOGGER.info("Using SQLite database (single-server mode)")
                SQLiteRepository(dataDir)
            }

            DatabaseType.MYSQL -> {
                CobbleCatchCombo.LOGGER.info("Using MySQL database (cross-server mode)")
                CobbleCatchCombo.LOGGER.info("  Host: ${config.mysql.host}:${config.mysql.port}")
                CobbleCatchCombo.LOGGER.info("  Database: ${config.mysql.database}")
                CobbleCatchCombo.LOGGER.info("  Table prefix: ${config.mysql.tablePrefix}")
                MySQLRepository(config.mysql)
            }

            DatabaseType.MONGODB -> {
                CobbleCatchCombo.LOGGER.info("Using MongoDB database (cross-server mode)")
                CobbleCatchCombo.LOGGER.info("  Database: ${config.mongodb.database}")
                CobbleCatchCombo.LOGGER.info("  Collection: ${config.mongodb.collection}")
                MongoDBRepository(config.mongodb)
            }
        }
    }

    /**
     * Check if the configured database type supports cross-server synchronization.
     */
    fun isCrossServerEnabled(config: DatabaseConfig): Boolean {
        return when (DatabaseType.fromString(config.type)) {
            DatabaseType.SQLITE -> false
            DatabaseType.MYSQL, DatabaseType.MONGODB -> true
        }
    }

    /**
     * Get a human-readable description of the configured database.
     */
    fun getDatabaseDescription(config: DatabaseConfig): String {
        return when (val dbType = DatabaseType.fromString(config.type)) {
            DatabaseType.SQLITE -> "SQLite (local)"
            DatabaseType.MYSQL -> "MySQL (${config.mysql.host}:${config.mysql.port}/${config.mysql.database})"
            DatabaseType.MONGODB -> "MongoDB (${config.mongodb.database}/${config.mongodb.collection})"
        }
    }
}
