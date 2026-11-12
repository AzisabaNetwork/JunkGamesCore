/**
 * JunkGames platform API: the capability / provider SPI (spec §12).
 *
 * Contains only interfaces and data types, so server forks (Minogami) can implement Native
 * Providers with a compile-only dependency and register them to the ServicesManager.
 */
package net.azisaba.jg.platform

/** Identifier of a capability ("world", "structure", "view", "tick", "execution", ...). */
@JvmRecord
data class CapabilityId(val id: String) {
    init {
        require(id.matches(Regex("[a-z0-9_]+"))) { "invalid capability id: $id" }
    }

    override fun toString(): String = id

    companion object {
        @JvmField val WORLD = CapabilityId("world")
        @JvmField val STRUCTURE = CapabilityId("structure")
        @JvmField val VIEW = CapabilityId("view")
        @JvmField val TICK = CapabilityId("tick")

        /** How instance ticks are scheduled (main thread / region / per-world thread). */
        @JvmField val EXECUTION = CapabilityId("execution")
    }
}

/**
 * Optional feature a provider advertises. Blocks declare requirements as
 * `"<capability>:<FEATURE>"` (e.g. `view:CHUNK_LEVEL_VIEW`).
 */
@JvmRecord
data class Feature(val name: String) {
    override fun toString(): String = name

    companion object {
        @JvmField val IN_MEMORY = Feature("IN_MEMORY")
        @JvmField val ASYNC_LOAD = Feature("ASYNC_LOAD")
        @JvmField val COPY_ON_WRITE = Feature("COPY_ON_WRITE")
        @JvmField val SNAPSHOT_RESET = Feature("SNAPSHOT_RESET")
        @JvmField val CHUNK_LEVEL_VIEW = Feature("CHUNK_LEVEL_VIEW")
        @JvmField val FAKE_BLOCK = Feature("FAKE_BLOCK")
        @JvmField val GLOW = Feature("GLOW")
        @JvmField val HIDE_ENTITY = Feature("HIDE_ENTITY")
        @JvmField val SCHEMATIC_NBT = Feature("SCHEMATIC_NBT")
        @JvmField val SCHEMATIC_SCHEM = Feature("SCHEMATIC_SCHEM")
        @JvmField val TICK_FREEZE = Feature("TICK_FREEZE")
        @JvmField val TICK_RATE = Feature("TICK_RATE")

        /** Execution provider can hand work to another world's next tick without blocking. */
        @JvmField val HANDOFF = Feature("HANDOFF")
    }
}

/** Threading model of the server, declared by the selected execution provider. */
enum class ThreadingModel {
    /** Vanilla Paper: everything runs on the main thread. */
    MAIN_THREAD,

    /** Folia family: region threads. */
    REGIONIZED,

    /** Per-world threads ticking in parallel with a per-tick barrier (Chiyogami / Minogami). */
    PER_WORLD_PARALLEL,
}

/** Result of a provider self-check. */
@JvmRecord
data class HealthStatus(val healthy: Boolean, val message: String) {
    companion object {
        @JvmStatic fun ok() = HealthStatus(true, "ok")
        @JvmStatic fun unhealthy(message: String) = HealthStatus(false, message)
    }
}

/**
 * Base interface of every capability implementation.
 *
 * Providers are registered to Bukkit's `ServicesManager` under their concrete capability
 * interface (e.g. [WorldProvider]). The core picks one provider per capability following
 * `providers.yml`, once at startup.
 */
interface Provider {
    val capability: CapabilityId

    /** Short name used in providers.yml ("native", "paper", "multiverse", ...). */
    val name: String

    /** Higher wins when providers.yml does not mention this provider. */
    val priority: Int

    val features: Set<Feature>

    val threading: ThreadingModel

    fun health(): HealthStatus

    fun has(feature: Feature): Boolean = feature in features
}
