package net.azisaba.jg.core.provider.paper

import net.azisaba.jg.platform.ExecutionProvider
import net.azisaba.jg.platform.Feature
import net.azisaba.jg.platform.HealthStatus
import net.azisaba.jg.platform.ThreadingModel
import net.azisaba.jg.platform.TickHandle
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.Plugin
import java.util.Random

/** Fallback for vanilla Paper: everything on the main thread, one repeating task per instance. */
class PaperExecutionProvider : ExecutionProvider {
    override val name = "paper"
    override val priority = 0
    override val features = setOf(Feature.HANDOFF)
    override val threading = ThreadingModel.MAIN_THREAD

    override fun health() =
        if (FoliaExecutionProvider.isFolia) HealthStatus.unhealthy("Folia ではメインスレッドのスケジューラを使えません") else HealthStatus.ok()

    override fun scheduleWorldTick(plugin: Plugin, world: World, tick: Runnable): TickHandle {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, tick, 1L, 1L)
        return TickHandle { task.cancel() }
    }

    override fun handoff(plugin: Plugin, world: World, task: Runnable) {
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun runGlobal(plugin: Plugin, task: Runnable) {
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }
}

/**
 * Folia family (REGIONIZED). Instance ticks run on the region owning the arena's origin chunk, so
 * arenas should fit in one region.
 */
class FoliaExecutionProvider : ExecutionProvider {
    override val name = "folia"
    override val priority = 10
    override val features = setOf(Feature.HANDOFF)
    override val threading = ThreadingModel.REGIONIZED

    override fun health() = if (isFolia) HealthStatus.ok() else HealthStatus.unhealthy("Folia ではありません")

    override fun scheduleWorldTick(plugin: Plugin, world: World, tick: Runnable): TickHandle {
        val t = Bukkit.getRegionScheduler().runAtFixedRate(plugin, world, 0, 0, { tick.run() }, 1L, 1L)
        return TickHandle { t.cancel() }
    }

    override fun handoff(plugin: Plugin, world: World, task: Runnable) {
        Bukkit.getRegionScheduler().run(plugin, world, 0, 0) { task.run() }
    }

    override fun runGlobal(plugin: Plugin, task: Runnable) {
        Bukkit.getGlobalRegionScheduler().run(plugin) { task.run() }
    }

    companion object {
        val isFolia: Boolean by lazy {
            runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess
        }
    }
}

/** Empty world. */
class VoidGenerator : ChunkGenerator() {
    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
    override fun canSpawn(world: World, x: Int, z: Int) = true
    override fun getFixedSpawnLocation(world: World, random: Random) = Location(world, 0.5, 64.0, 0.5)
}
