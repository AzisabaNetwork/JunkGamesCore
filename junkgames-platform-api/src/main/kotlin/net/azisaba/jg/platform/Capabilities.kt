package net.azisaba.jg.platform

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Inclusive axis-aligned block box. */
@JvmRecord
data class BlockBox(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    /** Point containment (a block occupies [min, max+1)). */
    fun containsPoint(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1

    fun volume(): Long = (maxX - minX + 1).toLong() * (maxY - minY + 1) * (maxZ - minZ + 1)

    companion object {
        @JvmStatic
        fun of(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
            BlockBox(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))
    }
}

/**
 * Key of an arena world. Ephemeral worlds always use `junkgames:arena/<game>/<instance>`
 * (users cannot choose it); persistent worlds are restricted to the `junkgames:` namespace.
 */
@JvmRecord
data class WorldKey(val key: NamespacedKey) {
    init {
        require(key.namespace == NAMESPACE) { "arena worlds must live under the $NAMESPACE: namespace: $key" }
    }

    val isEphemeralKey: Boolean get() = key.key.startsWith("arena/")

    /** World name derived from the key (no slashes). */
    fun worldName(): String = key.namespace + "_" + key.key.replace('/', '_')

    override fun toString(): String = key.toString()

    companion object {
        const val NAMESPACE = "junkgames"

        @JvmStatic
        fun ephemeral(gameId: String, instanceId: String) = WorldKey(NamespacedKey(NAMESPACE, "arena/$gameId/$instanceId"))

        @JvmStatic
        fun persistent(path: String) = WorldKey(NamespacedKey(NAMESPACE, path))
    }
}

enum class ResetMode { RECREATE, SNAPSHOT, NONE }

/**
 * How a world for a map is prepared (spec §12.5).
 *
 * @property ref template ref, schematic path, generator id or existing world key
 * @property origin paste origin for schematics (`[x,y,z]`)
 * @property generator void / flat / vanilla / custom:&lt;id&gt;
 * @property snapshotBoxes boxes captured when [reset] is [ResetMode.SNAPSHOT]
 * @property gameId origin metadata recorded into the world
 */
data class WorldSpec(
    val sourceType: SourceType,
    val ref: String,
    val origin: List<Int>?,
    val createIfMissing: Boolean,
    val generator: String,
    val ephemeral: Boolean,
    val reset: ResetMode,
    val snapshotBoxes: List<BlockBox>,
    val gameId: String = "",
    val gameVersion: Int = 0,
) {
    enum class SourceType { TEMPLATE, SCHEMATIC, GENERATE, EXISTING }
}

/** A prepared arena world owned by one instance (or shared, for `existing`). */
interface ArenaWorld {
    val key: WorldKey
    val world: World
    val spec: WorldSpec

    /** Name of the provider that created it. */
    val providerName: String
}

/** Capability `world`. The instance manager never touches world files directly. */
interface WorldProvider : Provider {
    override val capability: CapabilityId get() = CapabilityId.WORLD

    /** Prepares (copies / creates / loads) a world; it is loaded when the future completes. */
    fun prepare(spec: WorldSpec, key: WorldKey): CompletableFuture<ArenaWorld>

    fun reset(world: ArenaWorld, mode: ResetMode): CompletableFuture<Void?>

    /** Unloads the world; deletes its files when it is ephemeral. */
    fun dispose(world: ArenaWorld): CompletableFuture<Void?>

    fun exists(key: WorldKey): Boolean

    /** Removes leftovers of ephemeral worlds after a crash. Called once on startup. */
    fun cleanupOrphans() {}
}

/** Capability `structure`. Large edits must be split over ticks by the implementation. */
interface StructureProvider : Provider {
    override val capability: CapabilityId get() = CapabilityId.STRUCTURE

    fun paste(world: World, schematic: Path, x: Int, y: Int, z: Int): CompletableFuture<Void?>

    /** Fills a box, never touching more than [maxBlocksPerTick] blocks per tick. */
    fun fill(world: World, box: BlockBox, data: BlockData, maxBlocksPerTick: Int): CompletableFuture<Void?>

    /** Captures the current state of the boxes for `reset: snapshot`. */
    fun snapshot(world: World, boxes: List<BlockBox>): CompletableFuture<Snapshot>

    fun restore(world: World, snapshot: Snapshot, maxBlocksPerTick: Int): CompletableFuture<Void?>

    interface Snapshot {
        val blockCount: Long
    }
}

/** Handle of a per-viewer visual change. Closing it reverts the change (idempotent). */
interface ViewHandle : AutoCloseable {
    override fun close()
    val isClosed: Boolean
}

/** Capability `view`: per-player visuals. Raw packets are never exposed to blocks (spec §12.7). */
interface ViewProvider : Provider {
    override val capability: CapabilityId get() = CapabilityId.VIEW

    /** Requires [Feature.FAKE_BLOCK]. */
    fun fakeBlock(viewer: Player, loc: Location, data: BlockData): ViewHandle

    /** Requires [Feature.GLOW]; otherwise throws [UnsupportedOperationException]. */
    fun glow(viewer: Player, target: Entity, color: NamedTextColor): ViewHandle

    /** Requires [Feature.HIDE_ENTITY]. */
    fun hide(viewer: Player, target: Entity): ViewHandle
}

/** Capability `tick`: per-world tick control. */
interface TickProvider : Provider {
    override val capability: CapabilityId get() = CapabilityId.TICK

    /** Requires [Feature.TICK_FREEZE]. */
    fun setFrozen(world: World, frozen: Boolean)

    /** Requires [Feature.TICK_RATE]. */
    fun setTickRate(world: World, ticksPerSecond: Float)
}

fun interface TickHandle : AutoCloseable {
    override fun close()
}

/**
 * Capability `execution`: how the core runs code "as part of a world's tick".
 *
 * A [ThreadingModel.PER_WORLD_PARALLEL] fork implements [scheduleWorldTick] with a hook that runs
 * on the world's own WorldThread every tick (spec §12.8 convention 1), and [handoff] by queueing
 * to the target world's next tick (convention 4).
 */
interface ExecutionProvider : Provider {
    override val capability: CapabilityId get() = CapabilityId.EXECUTION

    /** Calls [tick] once per tick on the thread that ticks [world]. */
    fun scheduleWorldTick(plugin: Plugin, world: World, tick: Runnable): TickHandle

    /** Runs [task] at the beginning of [world]'s next tick on its thread. Never blocks. */
    fun handoff(plugin: Plugin, world: World, task: Runnable)

    /** Runs [task] on a thread that may touch global (non-world) state. */
    fun runGlobal(plugin: Plugin, task: Runnable)
}
