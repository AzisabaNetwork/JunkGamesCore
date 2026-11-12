package net.azisaba.jg.core.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.azisaba.jg.platform.CapabilityId
import net.azisaba.jg.platform.ExecutionProvider
import net.azisaba.jg.platform.Feature
import net.azisaba.jg.platform.HealthStatus
import net.azisaba.jg.platform.Provider
import net.azisaba.jg.platform.StructureProvider
import net.azisaba.jg.platform.TickProvider
import net.azisaba.jg.platform.ViewProvider
import net.azisaba.jg.platform.WorldProvider
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import java.util.logging.Logger

/**
 * Capability registry (spec §12.4): for each capability, picks the first healthy provider in
 * `providers.yml` order. Providers come from the core's builtins and from Bukkit's
 * ServicesManager (external plugins and server forks register there). Selection happens once at
 * startup; providers are never switched while running.
 */
class CapabilityRegistry {
    private val selected = LinkedHashMap<CapabilityId, Provider>()
    val rejected = LinkedHashMap<CapabilityId, List<String>>()

    fun select(config: ConfigurationSection, builtins: Collection<Provider>, log: Logger) {
        selected.clear()
        rejected.clear()
        for ((cap, type) in TYPES) {
            val candidates = ArrayList<Provider>()
            builtins.filterTo(candidates) { it.capability == cap }
            Bukkit.getServicesManager().getRegistrations(type).forEach { candidates += it.provider }
            val order = config.getStringList("${cap.id}.order")
            candidates.sortWith(
                compareBy<Provider> { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenByDescending { it.priority },
            )
            val rej = ArrayList<String>()
            for (p in candidates) {
                if (order.isNotEmpty() && p.name !in order) {
                    rej += "${p.name} (providers.yml にない)"
                    continue
                }
                val h = runCatching { p.health() }.getOrElse { HealthStatus.unhealthy(it.toString()) }
                if (!h.healthy) {
                    rej += "${p.name} (${h.message})"
                    continue
                }
                selected[cap] = p
                break
            }
            rejected[cap] = rej
            val p = selected[cap]
            log.info("capability $cap: ${p?.let { "${it.name} ${it.features}" } ?: "なし"}${if (rej.isEmpty()) "" else "  不採用: $rej"}")
        }
    }

    fun <T : Provider> get(type: Class<T>): T? = selected.values.firstOrNull { type.isInstance(it) }?.let(type::cast)

    operator fun get(id: CapabilityId): Provider? = selected[id]

    val execution: ExecutionProvider get() = get(ExecutionProvider::class.java) ?: error("no execution provider")

    /** `"view"` or `"view:GLOW"`. */
    fun satisfies(requirement: String): Boolean {
        val parts = requirement.split(":", limit = 2)
        val p = selected[CapabilityId(parts[0])] ?: return false
        return parts.size < 2 || p.has(Feature(parts[1]))
    }

    /** Requirements that cannot be satisfied here, with reasons (for the editor's grey-out). */
    fun unsatisfied(requirements: Set<String>): List<String> = requirements.filterNot { satisfies(it) }.map { r ->
        val parts = r.split(":", limit = 2)
        val p = selected[CapabilityId(parts[0])]
        if (p == null) "$r（能力 ${parts[0]} のProviderがありません）" else "$r（Provider ${p.name} は ${parts[1]} に対応していません）"
    }

    /** Selected providers and features, as reported in the repository "hello" (spec §10.2). */
    fun describe(): JsonObject = JsonObject().apply {
        for ((cap, p) in selected) {
            add(cap.id, JsonObject().apply {
                addProperty("name", p.name)
                addProperty("threading", p.threading.name)
                add("features", JsonArray().apply { p.features.forEach { add(it.name) } })
            })
        }
    }

    fun selected(): Map<CapabilityId, Provider> = selected

    companion object {
        private val TYPES: Map<CapabilityId, Class<out Provider>> = linkedMapOf(
            CapabilityId.EXECUTION to ExecutionProvider::class.java,
            CapabilityId.WORLD to WorldProvider::class.java,
            CapabilityId.STRUCTURE to StructureProvider::class.java,
            CapabilityId.VIEW to ViewProvider::class.java,
            CapabilityId.TICK to TickProvider::class.java,
        )
    }
}
