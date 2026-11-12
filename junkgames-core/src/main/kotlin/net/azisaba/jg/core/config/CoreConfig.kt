package net.azisaba.jg.core.config

import net.azisaba.jg.api.runtime.Limits
import org.bukkit.configuration.file.FileConfiguration

/** config.yml */
data class CoreConfig(
    val serverId: String,
    val limits: Limits,
    val maxInstances: Int,
    val allowPersistentWorldCreate: Boolean,
    val debugMessages: Boolean,
    /** map id -> number of pre-created worlds (spec §8.1 warm pool) */
    val warmPool: Map<String, Int>,
    val allowedCommandsInGame: List<String>,
) {
    companion object {
        fun load(c: FileConfiguration): CoreConfig {
            val d = Limits()
            val l = c.getConfigurationSection("limits")
            val limits = if (l == null) d else Limits(
                l.getInt("per-runner-budget", d.perRunnerBudget),
                l.getInt("per-instance-budget", d.perInstanceBudget),
                l.getInt("runaway-ticks", d.runawayTicks),
                l.getInt("max-runners", d.maxRunners),
                l.getInt("max-entities", d.maxEntities),
                l.getInt("max-fill-blocks-per-tick", d.maxFillBlocksPerTick),
                l.getInt("max-list-size", d.maxListSize),
                l.getInt("max-string-length", d.maxStringLength),
                l.getInt("max-call-depth", d.maxCallDepth),
                l.getInt("reporter-step-limit", d.reporterStepLimit),
                l.getInt("error-threshold-per-minute", d.errorThresholdPerMinute),
            )
            return CoreConfig(
                serverId = c.getString("server-id", "junkgames-1")!!,
                limits = limits,
                maxInstances = c.getInt("max-instances", 8),
                allowPersistentWorldCreate = c.getBoolean("allow-persistent-world-create", false),
                debugMessages = c.getBoolean("debug-messages", true),
                warmPool = c.getConfigurationSection("warm-pool")?.let { s -> s.getKeys(false).associateWith { maxOf(0, s.getInt(it)) } } ?: emptyMap(),
                allowedCommandsInGame = c.getStringList("allowed-commands-in-game").map { it.lowercase().removePrefix("/") },
            )
        }
    }
}
