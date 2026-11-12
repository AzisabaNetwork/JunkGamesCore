package net.azisaba.jg.core.provider.paper

import net.azisaba.jg.platform.BlockBox
import net.azisaba.jg.platform.ExecutionProvider
import net.azisaba.jg.platform.Feature
import net.azisaba.jg.platform.HealthStatus
import net.azisaba.jg.platform.StructureProvider
import net.azisaba.jg.platform.ThreadingModel
import net.azisaba.jg.platform.TickHandle
import net.azisaba.jg.platform.ViewHandle
import net.azisaba.jg.platform.ViewProvider
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CompletableFuture
import kotlin.io.path.name

/**
 * Fallback structure provider: vanilla structure files (`.nbt`) and fills / snapshots split over
 * ticks on the world's own thread.
 */
class PaperStructureProvider(private val plugin: Plugin, private val execution: () -> ExecutionProvider) : StructureProvider {
    override val name = "paper"
    override val priority = 0
    override val features = setOf(Feature.SCHEMATIC_NBT, Feature.SNAPSHOT_RESET)
    override val threading = ThreadingModel.MAIN_THREAD
    override fun health() = HealthStatus.ok()

    override fun paste(world: World, schematic: Path, x: Int, y: Int, z: Int): CompletableFuture<Void?> {
        if (!schematic.name.endsWith(".nbt")) {
            return CompletableFuture.failedFuture(
                UnsupportedOperationException("paper provider は .nbt（ストラクチャー）のみ対応です。.schem には WorldEdit/FAWE の Provider が必要です: ${schematic.name}"),
            )
        }
        if (!Files.isRegularFile(schematic)) return CompletableFuture.failedFuture(IllegalArgumentException("ファイルがありません: $schematic"))
        val f = CompletableFuture<Void?>()
        execution().handoff(plugin, world) {
            try {
                val s = Bukkit.getStructureManager().loadStructure(schematic.toFile())
                s.place(Location(world, x.toDouble(), y.toDouble(), z.toDouble()), true, StructureRotation.NONE, Mirror.NONE, 0, 1f, Random())
                f.complete(null)
            } catch (e: Exception) {
                f.completeExceptionally(e)
            }
        }
        return f
    }

    override fun fill(world: World, box: BlockBox, data: BlockData, maxBlocksPerTick: Int): CompletableFuture<Void?> =
        iterate(world, listOf(box), maxBlocksPerTick) { b -> if (b.blockData != data) b.setBlockData(data, false) }

    private class Snap(val boxes: List<BlockBox>, val data: Array<BlockData?>) : StructureProvider.Snapshot {
        override val blockCount: Long get() = data.size.toLong()
    }

    override fun snapshot(world: World, boxes: List<BlockBox>): CompletableFuture<StructureProvider.Snapshot> {
        val total = boxes.sumOf { it.volume() }
        if (total > MAX_SNAPSHOT_BLOCKS) {
            return CompletableFuture.failedFuture(IllegalArgumentException("スナップショットが大きすぎます（$total ブロック）。Native/FAWE Provider を使ってください"))
        }
        val data = arrayOfNulls<BlockData>(total.toInt())
        var i = 0
        return iterate(world, boxes, 65_536) { b -> data[i++] = b.blockData }.thenApply { Snap(boxes.toList(), data) }
    }

    override fun restore(world: World, snapshot: StructureProvider.Snapshot, maxBlocksPerTick: Int): CompletableFuture<Void?> {
        val s = snapshot as? Snap ?: return CompletableFuture.failedFuture(IllegalArgumentException("foreign snapshot"))
        var i = 0
        return iterate(world, s.boxes, maxBlocksPerTick) { b ->
            val d = s.data[i++]
            if (d != null && b.blockData != d) b.setBlockData(d, false)
        }
    }

    /** Visits every block of the boxes in a fixed order, at most [perTick] per tick, on the world's thread. */
    private fun iterate(world: World, boxes: List<BlockBox>, perTick: Int, action: (Block) -> Unit): CompletableFuture<Void?> {
        val f = CompletableFuture<Void?>()
        val budget = maxOf(1, perTick)
        var box = 0
        var x = 0
        var y = 0
        var z = 0
        var init = false
        var handle: TickHandle? = null
        val step = Runnable {
            if (f.isDone) return@Runnable
            try {
                var done = 0
                while (box < boxes.size && done < budget) {
                    val b = boxes[box]
                    if (!init) {
                        x = b.minX; y = b.minY; z = b.minZ; init = true
                    }
                    action(world.getBlockAt(x, y, z))
                    done++
                    if (++x > b.maxX) {
                        x = b.minX
                        if (++z > b.maxZ) {
                            z = b.minZ
                            if (++y > b.maxY) {
                                box++
                                init = false
                            }
                        }
                    }
                }
                if (box >= boxes.size) {
                    f.complete(null)
                    handle?.close()
                }
            } catch (e: RuntimeException) {
                f.completeExceptionally(e)
                handle?.close()
            }
        }
        execution().handoff(plugin, world) {
            step.run()
            if (!f.isDone) handle = execution().scheduleWorldTick(plugin, world, step)
        }
        return f
    }

    private companion object {
        /** Hard cap for snapshots kept in memory by this fallback. */
        const val MAX_SNAPSHOT_BLOCKS = 4_000_000L
    }
}

/** Fallback view provider using `sendBlockChange` and `hideEntity`. No per-viewer glow. */
class PaperViewProvider(private val plugin: Plugin) : ViewProvider {
    override val name = "paper"
    override val priority = 0
    override val features = setOf(Feature.FAKE_BLOCK, Feature.HIDE_ENTITY)
    override val threading = ThreadingModel.MAIN_THREAD
    override fun health() = HealthStatus.ok()

    override fun fakeBlock(viewer: Player, loc: Location, data: BlockData): ViewHandle {
        val at = loc.toBlockLocation()
        viewer.sendBlockChange(at, data)
        return Handle { if (viewer.isOnline && viewer.world == at.world) viewer.sendBlockChange(at, at.block.blockData) }
    }

    override fun glow(viewer: Player, target: Entity, color: NamedTextColor): ViewHandle =
        throw UnsupportedOperationException("paper provider does not support per-viewer glow")

    override fun hide(viewer: Player, target: Entity): ViewHandle {
        viewer.hideEntity(plugin, target)
        return Handle { if (viewer.isOnline) viewer.showEntity(plugin, target) }
    }

    private class Handle(private val undo: () -> Unit) : ViewHandle {
        override var isClosed = false
            private set

        override fun close() {
            if (isClosed) return
            isClosed = true
            undo()
        }
    }
}
