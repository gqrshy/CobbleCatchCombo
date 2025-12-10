package com.pokemon.catchcombo.config

import kotlinx.serialization.Serializable

@Serializable
data class CatchComboConfig(
    val database: DatabaseConfig = DatabaseConfig(),
    val combo: ComboConfig = ComboConfig(),
    val shinyBoost: ShinyBoostConfig = ShinyBoostConfig(),
    val ivBoost: IvBoostConfig = IvBoostConfig(),
    val display: DisplayConfig = DisplayConfig(),
    val notifications: NotificationConfig = NotificationConfig(),
    val integrations: IntegrationsConfig = IntegrationsConfig()
)

@Serializable
data class DatabaseConfig(
    val type: String = "sqlite",
    val sqlite: SqliteConfig = SqliteConfig(),
    val mysql: MysqlConfig = MysqlConfig(),
    val mongodb: MongodbConfig = MongodbConfig()
)

@Serializable
data class SqliteConfig(
    val filename: String = "cobblecatchcombo.db"
)

@Serializable
data class MysqlConfig(
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "cobblecatchcombo",
    val username: String = "root",
    val password: String = "",
    val tablePrefix: String = "ccc_"
)

@Serializable
data class MongodbConfig(
    val connectionString: String = "mongodb://localhost:27017",
    val database: String = "cobblecatchcombo",
    val collection: String = "combos"
)

@Serializable
data class ComboConfig(
    val resetOnPlayerFlee: Boolean = true,
    val resetOnPlayerDeath: Boolean = false,
    val resetOnWildDefeat: Boolean = true,
    val treatEvolutionLineAsSameSpecies: Boolean = false
)

@Serializable
data class ShinyBoostConfig(
    val enabled: Boolean = true,
    val onlyChainedSpecies: Boolean = true,
    val tiers: List<ShinyTier> = listOf(
        ShinyTier(0, 1.0),
        ShinyTier(10, 1.5),
        ShinyTier(20, 2.0),
        ShinyTier(30, 2.5),
        ShinyTier(50, 3.0),
        ShinyTier(100, 4.0)
    )
)

@Serializable
data class ShinyTier(
    val minCombo: Int,
    val multiplier: Double
)

@Serializable
data class IvBoostConfig(
    val enabled: Boolean = true,
    val tiers: List<IvTier> = listOf(
        IvTier(0, 0),
        IvTier(10, 1),
        IvTier(30, 2),
        IvTier(50, 3),
        IvTier(100, 4)
    )
)

@Serializable
data class IvTier(
    val minCombo: Int,
    val guaranteedPerfectIVs: Int
)

@Serializable
data class DisplayConfig(
    val bossBar: BossBarConfig = BossBarConfig(),
    val actionBar: ActionBarConfig = ActionBarConfig()
)

@Serializable
data class BossBarConfig(
    val enabled: Boolean = true,
    val color: String = "YELLOW",
    val style: String = "SEGMENTED_10",
    val showDurationSeconds: Int = 5,
    val showProgressToNextTier: Boolean = true
)

@Serializable
data class ActionBarConfig(
    val enabled: Boolean = false,
    val showDurationSeconds: Int = 5
)

@Serializable
data class NotificationConfig(
    val onComboBreak: ComboBreakNotificationConfig = ComboBreakNotificationConfig(),
    val onMilestone: MilestoneNotificationConfig = MilestoneNotificationConfig()
)

@Serializable
data class ComboBreakNotificationConfig(
    val enabled: Boolean = true,
    val sendChat: Boolean = true,
    val minComboToNotify: Int = 5
)

@Serializable
data class MilestoneNotificationConfig(
    val enabled: Boolean = true,
    val sendChat: Boolean = true,
    val milestones: List<Int> = listOf(10, 25, 50, 100, 200)
)

@Serializable
data class IntegrationsConfig(
    val textPlaceholderApi: TextPlaceholderApiConfig = TextPlaceholderApiConfig()
)

@Serializable
data class TextPlaceholderApiConfig(
    val enabled: Boolean = true
)
