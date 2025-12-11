package com.pokemon.catchcombo.data

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.pokemon.catchcombo.CobbleCatchCombo
import com.pokemon.catchcombo.config.MongodbConfig
import org.bson.Document
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MongoDB-based repository for combo data persistence.
 * Supports cross-server synchronization via shared MongoDB database.
 *
 * Features:
 * - Document-based storage with automatic indexing
 * - Thread-safe operations with connection pool (managed by driver)
 * - Local cache with staleness tracking for cross-server sync
 * - Flexible schema for future extensions
 *
 * Cross-server considerations:
 * - Data is written immediately to database for cross-server visibility
 * - Cache is refreshed on access if stale
 * - MongoDB's native replication support for high availability
 */
class MongoDBRepository(private val config: MongodbConfig) : ComboRepository {
    private var mongoClient: MongoClient? = null
    private var database: MongoDatabase? = null
    private var collection: MongoCollection<Document>? = null
    private val cache = ConcurrentHashMap<UUID, CachedComboData>()

    // Cache entry with staleness tracking
    private data class CachedComboData(
        val data: ComboData,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        // Cache is considered stale after 5 seconds for cross-server sync
        fun isStale(): Boolean = System.currentTimeMillis() - cachedAt > 5000
    }

    override fun initialize() {
        try {
            // Create MongoDB client from connection string
            mongoClient = MongoClients.create(config.connectionString)
            database = mongoClient!!.getDatabase(config.database)
            collection = database!!.getCollection(config.collection)

            // Create indexes for efficient queries
            createIndexes()

            CobbleCatchCombo.LOGGER.info("MongoDB connected: ${config.database}/${config.collection}")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to initialize MongoDB database", e)
            throw e
        }
    }

    private fun createIndexes() {
        try {
            // Index on player_uuid for fast lookups (should be unique)
            collection?.createIndex(
                Indexes.ascending("player_uuid"),
                IndexOptions().unique(true).background(true)
            )

            // Index on last_updated for potential cleanup queries
            collection?.createIndex(
                Indexes.descending("last_updated"),
                IndexOptions().background(true)
            )

            CobbleCatchCombo.LOGGER.info("MongoDB indexes created/verified")
        } catch (e: Exception) {
            // Index might already exist, that's fine
            CobbleCatchCombo.LOGGER.debug("MongoDB index creation: ${e.message}")
        }
    }

    override fun shutdown() {
        try {
            // Flush all cached data to database
            cache.values.forEach { cached ->
                saveToDatabase(cached.data)
            }
            cache.clear()

            mongoClient?.close()
            mongoClient = null
            database = null
            collection = null
            CobbleCatchCombo.LOGGER.info("MongoDB connection closed")
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Error during MongoDB shutdown", e)
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
            val doc = collection?.find(Filters.eq("player_uuid", playerUuid.toString()))?.first()
            doc?.let { documentToComboData(it) }
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load combo data from MongoDB for $playerUuid", e)
            null
        }
    }

    private fun documentToComboData(doc: Document): ComboData {
        return ComboData(
            playerUuid = UUID.fromString(doc.getString("player_uuid")),
            chainedSpecies = doc.getString("chained_species"),
            comboCount = doc.getInteger("combo_count", 0),
            maxCombo = doc.getInteger("max_combo", 0),
            lastUpdated = doc.getLong("last_updated") ?: System.currentTimeMillis()
        )
    }

    private fun comboDataToDocument(data: ComboData): Document {
        return Document().apply {
            put("player_uuid", data.playerUuid.toString())
            put("chained_species", data.chainedSpecies)
            put("combo_count", data.comboCount)
            put("max_combo", data.maxCombo)
            put("last_updated", data.lastUpdated)
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
            val doc = comboDataToDocument(data)
            collection?.replaceOne(
                Filters.eq("player_uuid", data.playerUuid.toString()),
                doc,
                ReplaceOptions().upsert(true)
            )
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to save combo data to MongoDB for ${data.playerUuid}", e)
        }
    }

    override fun deleteComboData(playerUuid: UUID) {
        cache.remove(playerUuid)
        try {
            collection?.deleteOne(Filters.eq("player_uuid", playerUuid.toString()))
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to delete combo data from MongoDB for $playerUuid", e)
        }
    }

    override fun getAllComboData(): List<ComboData> {
        // For cross-server, load fresh from database
        return try {
            val result = mutableListOf<ComboData>()
            collection?.find()?.forEach { doc ->
                val data = documentToComboData(doc)
                result.add(data)
                // Update cache while we're at it
                cache[data.playerUuid] = CachedComboData(data)
            }
            result
        } catch (e: Exception) {
            CobbleCatchCombo.LOGGER.error("Failed to load all combo data from MongoDB", e)
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
        CobbleCatchCombo.LOGGER.debug("MongoDB cache refreshed with ${cache.size} entries")
    }
}
