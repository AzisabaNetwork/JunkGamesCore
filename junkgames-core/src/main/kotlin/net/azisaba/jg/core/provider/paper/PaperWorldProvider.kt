package net.azisaba.jg.core.provider.paper

import com.google.gson.JsonObject
import net.azisaba.jg.platform.ArenaWorld
import net.azisaba.jg.platform.ExecutionProvider
import net.azisaba.jg.platform.Feature
import net.azisaba.jg.platform.HealthStatus
import net.azisaba.jg.platform.ResetMode
import net.azisaba.jg.platform.StructureProvider
import net.azisaba.jg.platform.ThreadingModel
import net.azisaba.jg.platform.WorldKey
import net.azisaba.jg.platform.WorldProvider
import net.azisaba.jg.platform.WorldSpec
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRules
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * World folder layout. Since 26.x worlds live in `<level>/dimensions/<ns>/<path>/` (spec §8.1);
 * older servers use `<container>/<name>/`. This object is the only place that knows about it.
 */
object WorldFiles {
    private val SKIP = setOf("session.lock", "uid.dat")
    const val META_FILE = "junkgames-world.json"

    /** Root folder of the main level (contains level.dat). */
    fun levelRoot(): Path = Bukkit.getWorlds().first().worldPath

    fun dimensionLayout(): Boolean = levelRoot().resolve("dimensions").isDirectory()

    /** Folder the server will use for a world with this key. */
    fun folderOf(key: WorldKey): Path =
        if (dimensionLayout()) levelRoot().resolve("dimensions").resolve(key.key.namespace).resolve(key.key.key)
        else Bukkit.getWorldContainer().toPath().resolve(key.worldName())

    /** Folder that may contain ephemeral arenas (for orphan cleanup). */
    fun arenaRoot(): Path =
        if (dimensionLayout()) levelRoot().resolve("dimensions").resolve(WorldKey.NAMESPACE).resolve("arena")
        else Bukkit.getWorldContainer().toPath()

    fun copy(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.walk(from).use { s ->
            s.forEach { p ->
                val dst = to.resolve(from.relativize(p).toString())
                when {
                    Files.isDirectory(p) -> Files.createDirectories(dst)
                    p.name !in SKIP -> Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    fun delete(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

/**
 * Fallback world provider: [WorldCreator] plus file copies (spec §12.2). Copies run on the I/O
 * executor; world creation / unloading runs on the global thread.
 */
class PaperWorldProvider(
    private val plugin: Plugin,
    private val templates: Path,
    private val schematics: Path,
    private val io: Executor,
    private val execution: () -> ExecutionProvider,
    private val structure: () -> StructureProvider?,
    private val allowPersistentCreate: Boolean,
) : WorldProvider {
    override val name = "paper"
    override val priority = 0
    override val features = setOf(Feature.SNAPSHOT_RESET)
    override val threading = ThreadingModel.MAIN_THREAD
    override fun health() = HealthStatus.ok()

    private class Arena(override val key: WorldKey, override val world: World, override val spec: WorldSpec) : ArenaWorld {
        override val providerName = "paper"
    }

    fun templateFolder(ref: String): Path {
        val r = ref.removePrefix("templates/")
        require(".." !in r) { "invalid template ref: $ref" }
        return templates.resolve(r)
    }

    override fun prepare(spec: WorldSpec, key: WorldKey): CompletableFuture<ArenaWorld> = try {
        when (spec.sourceType) {
            WorldSpec.SourceType.EXISTING -> prepareExisting(spec)
            WorldSpec.SourceType.TEMPLATE -> {
                val src = templateFolder(spec.ref)
                when {
                    src.isDirectory() -> create(spec, key, src)
                    !spec.createIfMissing -> CompletableFuture.failedFuture(IllegalStateException("テンプレートがありません: ${spec.ref}"))
                    else -> {
                        plugin.logger.warning("テンプレート ${spec.ref} がないため、${spec.generator} で空のワールドを作ります")
                        create(spec, key, null)
                    }
                }
            }
            WorldSpec.SourceType.SCHEMATIC -> create(spec, key, null).thenCompose { a ->
                val sp = structure() ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("structure 能力がありません"))
                val o = spec.origin ?: listOf(0, 64, 0)
                sp.paste(a.world, schematics.resolve(spec.ref), o[0], o[1], o[2]).thenApply { a }
            }
            WorldSpec.SourceType.GENERATE -> create(spec, key, null)
        }
    } catch (e: RuntimeException) {
        CompletableFuture.failedFuture(e)
    }

    private fun prepareExisting(spec: WorldSpec): CompletableFuture<ArenaWorld> {
        val f = CompletableFuture<ArenaWorld>()
        global {
            val k = NamespacedKey.fromString(spec.ref)
            var w = if (k != null) Bukkit.getWorld(k) else Bukkit.getWorld(spec.ref)
            if (w == null && k != null && k.namespace == WorldKey.NAMESPACE && WorldFiles.folderOf(WorldKey(k)).isDirectory()) {
                w = WorldCreator(k).createWorld()
            }
            if (w == null) f.completeExceptionally(IllegalStateException("既存ワールドが見つかりません: ${spec.ref}"))
            // label only: existing worlds are never created or deleted by us
            else f.complete(Arena(WorldKey.persistent("existing/${w.key.namespace}/${w.key.key}"), w, spec))
        }
        return f
    }

    /** Creates (optionally filled from a template folder) a world under [key]. */
    private fun create(spec: WorldSpec, key: WorldKey, template: Path?): CompletableFuture<ArenaWorld> {
        if (!spec.ephemeral && !allowPersistentCreate && !exists(key)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("永続ワールド $key の自動作成は許可されていません（config: allow-persistent-world-create）"),
            )
        }
        val folder = CompletableFuture<Path>()
        global { folder.complete(WorldFiles.folderOf(key)) }
        return folder.thenApplyAsync({ dir ->
            if (spec.ephemeral) WorldFiles.delete(dir)
            if (template != null) WorldFiles.copy(template, dir)
            Files.createDirectories(dir)
            writeMeta(dir, spec, key)
            dir
        }, io).thenCompose { dir ->
            val f = CompletableFuture<ArenaWorld>()
            global {
                try {
                    val wc = WorldCreator(key.key)
                    applyGenerator(wc, spec.generator)
                    val w = wc.createWorld() ?: error("createWorld returned null")
                    configure(w, spec.ephemeral)
                    if (w.worldPath.toAbsolutePath().normalize() != dir.toAbsolutePath().normalize()) {
                        plugin.logger.warning("ワールド $key のフォルダが想定と異なります: ${w.worldPath}（想定: $dir）。テンプレートが反映されていない可能性があります")
                    }
                    f.complete(Arena(key, w, spec))
                } catch (e: RuntimeException) {
                    f.completeExceptionally(e)
                }
            }
            f
        }
    }

    private fun applyGenerator(wc: WorldCreator, generator: String) {
        when {
            generator == "void" -> wc.generator(VoidGenerator())
            generator == "flat" -> wc.type(WorldType.FLAT)
            generator == "vanilla" -> {}
            generator.startsWith("custom:") -> wc.generator(generator.removePrefix("custom:"))
            else -> wc.generator(VoidGenerator())
        }
    }

    private fun configure(w: World, ephemeral: Boolean) {
        w.isAutoSave = !ephemeral
        w.difficulty = Difficulty.NORMAL
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        w.setGameRule(GameRules.ADVANCE_TIME, false)
        w.setGameRule(GameRules.ADVANCE_WEATHER, false)
        w.setGameRule(GameRules.SPAWN_MOBS, false)
        w.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false)
        w.time = 6000
    }

    private fun writeMeta(dir: Path, spec: WorldSpec, key: WorldKey) {
        // spec §12.5: record where an auto-created world came from
        val o = JsonObject().apply {
            addProperty("key", key.toString())
            addProperty("lifecycle", if (spec.ephemeral) "ephemeral" else "persistent")
            addProperty("game", spec.gameId)
            addProperty("gameVersion", spec.gameVersion)
            addProperty("provider", name)
            addProperty("createdAt", Instant.now().toString())
        }
        Files.writeString(dir.resolve(WorldFiles.META_FILE), o.toString())
    }

    // SNAPSHOT is restored by the instance through the structure capability
    override fun reset(world: ArenaWorld, mode: ResetMode): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun dispose(world: ArenaWorld): CompletableFuture<Void?> {
        if (world.spec.sourceType == WorldSpec.SourceType.EXISTING) return CompletableFuture.completedFuture(null)
        val unloaded = CompletableFuture<Path?>()
        global {
            val w = world.world
            val dir = w.worldPath
            val fallback = Bukkit.getWorlds().first().spawnLocation
            w.players.forEach { it.teleport(fallback) }
            val ok = Bukkit.unloadWorld(w, !world.spec.ephemeral)
            if (!ok) plugin.logger.warning("ワールドをアンロードできませんでした: ${world.key}")
            unloaded.complete(if (ok) dir else null)
        }
        if (!world.spec.ephemeral) return unloaded.thenApply { null }
        return unloaded.thenAcceptAsync({ dir ->
            try {
                dir?.let { WorldFiles.delete(it) }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "ワールドの削除に失敗: $dir", e)
            }
        }, io).thenApply { null }
    }

    override fun exists(key: WorldKey) = Bukkit.getWorld(key.key) != null || WorldFiles.folderOf(key).isDirectory()

    override fun cleanupOrphans() {
        val root = WorldFiles.arenaRoot()
        if (!root.isDirectory()) return
        try {
            Files.walk(root, if (WorldFiles.dimensionLayout()) 3 else 1).use { s ->
                s.filter { it.name == WorldFiles.META_FILE }.toList()
            }.forEach { meta ->
                if (!Files.readString(meta).contains("\"lifecycle\":\"ephemeral\"")) return@forEach
                val dir = meta.parent
                if (Bukkit.getWorlds().any { it.worldPath.toAbsolutePath() == dir.toAbsolutePath() }) return@forEach
                WorldFiles.delete(dir)
                plugin.logger.info("前回の異常終了で残ったアリーナを削除しました: $dir")
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "アリーナの掃除に失敗しました", e)
        }
    }

    private fun global(r: () -> Unit) = execution().runGlobal(plugin, r)
}
