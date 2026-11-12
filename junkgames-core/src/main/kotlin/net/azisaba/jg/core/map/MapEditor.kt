package net.azisaba.jg.core.map

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.azisaba.jg.api.ir.MapDefinition
import net.azisaba.jg.core.Core
import net.azisaba.jg.core.definition.DefinitionStore
import net.azisaba.jg.core.instance.PlayerSnapshot
import net.azisaba.jg.core.provider.paper.PaperWorldProvider
import net.azisaba.jg.core.provider.paper.WorldFiles
import net.azisaba.jg.platform.ArenaWorld
import net.azisaba.jg.platform.BlockBox
import net.azisaba.jg.platform.ResetMode
import net.azisaba.jg.platform.WorldKey
import net.azisaba.jg.platform.WorldProvider
import net.azisaba.jg.platform.WorldSpec
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.roundToInt

/**
 * In-game map tool (spec §11): `/jgmap edit <map>` opens a working copy of the template world,
 * the wand registers points / regions, particles visualize them, and `/jgmap save` writes the
 * map definition and copies the world back to the template store.
 */
class MapEditor(private val core: Core) : Listener {
    private val mm = MiniMessage.miniMessage()
    private val wandKey = NamespacedKey(core.plugin, "map_wand")
    private val sessions = ConcurrentHashMap<UUID, Session>()

    private class Session(
        val mapId: String,
        val templateRef: String,
        val arena: ArenaWorld,
        val snapshot: PlayerSnapshot,
        val compatibleGames: MutableList<String>,
        val displayName: String,
        val points: MutableMap<String, MutableList<MapDefinition.Point>>,
        val regions: MutableMap<String, MutableList<BlockBox>>,
    ) {
        var pos1: Location? = null
        var pos2: Location? = null
    }

    fun session(p: Player): Boolean = sessions.containsKey(p.uniqueId)

    fun edit(p: Player, mapId: String) {
        if (!mapId.matches(Regex("[a-z0-9_]{1,64}"))) return p.msg("<red>マップIDは [a-z0-9_] で指定してください")
        if (session(p)) return p.msg("<red>すでに編集中です。/jgmap save か /jgmap exit を実行してください")
        if (core.instances.byPlayer(p.uniqueId) != null) return p.msg("<red>ゲーム中は編集できません")
        val existing = core.definitions.map(mapId)
        val ref = existing?.world?.ref?.takeIf { existing.world.sourceType == WorldSpec.SourceType.TEMPLATE } ?: "templates/$mapId"
        val spec = WorldSpec(WorldSpec.SourceType.TEMPLATE, ref, null, true, existing?.world?.generator ?: "void", true, ResetMode.RECREATE, emptyList())
        val provider = core.capabilities.get(WorldProvider::class.java) ?: return p.msg("<red>world 能力がありません")
        p.msg("<gray>編集用ワールドを用意しています…")
        provider.prepare(spec, WorldKey.ephemeral("edit_$mapId", UUID.randomUUID().toString().take(6))).whenComplete { arena, e ->
            if (e != null) {
                core.plugin.logger.log(Level.WARNING, "編集用ワールドを用意できません: $mapId", e)
                return@whenComplete p.msg("<red>編集用ワールドを用意できませんでした: ${e.message}")
            }
            core.capabilities.execution.handoff(core.plugin, p.world) {
                val snap = PlayerSnapshot.capture(p)
                core.snapshots.save(snap)
                sessions[p.uniqueId] = Session(
                    mapId, ref, arena, snap,
                    existing?.compatibleGames?.toMutableList() ?: mutableListOf(),
                    existing?.displayName ?: mapId,
                    existing?.points?.mapValuesTo(LinkedHashMap()) { it.value.toMutableList() } ?: LinkedHashMap(),
                    existing?.regions?.mapValuesTo(LinkedHashMap()) { it.value.toMutableList() } ?: LinkedHashMap(),
                )
                p.teleportAsync(arena.world.spawnLocation).thenRun {
                    core.capabilities.execution.handoff(core.plugin, arena.world) {
                        PlayerSnapshot.reset(p, GameMode.CREATIVE)
                        p.inventory.addItem(wand())
                        p.msg("<green>$mapId を編集中です。<gray>ワンド: 左クリック=pos1 / 右クリック=pos2")
                        p.msg("<gray>/jgmap point add <名前> ・ /jgmap region add <名前> ・ /jgmap game add <ゲーム> ・ /jgmap save ・ /jgmap exit")
                    }
                }
            }
        }
    }

    fun addPoint(p: Player, name: String) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        val l = p.location
        // snap to half blocks and 45° steps so spawns look tidy
        val pt = MapDefinition.Point(
            (l.x * 2).roundToInt() / 2.0, (l.y * 2).roundToInt() / 2.0, (l.z * 2).roundToInt() / 2.0,
            ((l.yaw / 45f).roundToInt() * 45f), 0f,
        )
        s.points.getOrPut(name) { ArrayList() } += pt
        p.msg("<green>地点 $name を追加しました（${s.points[name]!!.size} 個目）: ${pt.x}, ${pt.y}, ${pt.z}")
    }

    fun clearPoint(p: Player, name: String) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        p.msg(if (s.points.remove(name) != null) "<yellow>地点 $name を削除しました" else "<red>地点 $name はありません")
    }

    fun addRegion(p: Player, name: String) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        val a = s.pos1
        val b = s.pos2
        if (a == null || b == null) return p.msg("<red>ワンドで pos1 と pos2 を指定してください")
        val box = BlockBox.of(a.blockX, a.blockY, a.blockZ, b.blockX, b.blockY, b.blockZ)
        s.regions.getOrPut(name) { ArrayList() } += box
        p.msg("<green>領域 $name に直方体を追加しました（${box.volume()} ブロック）")
    }

    fun clearRegion(p: Player, name: String) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        p.msg(if (s.regions.remove(name) != null) "<yellow>領域 $name を削除しました" else "<red>領域 $name はありません")
    }

    fun addGame(p: Player, game: String) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        if (game !in s.compatibleGames) s.compatibleGames += game
        p.msg("<green>対応ゲーム: ${s.compatibleGames}")
    }

    fun info(p: Player) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        p.msg("<gold>${s.mapId}<gray> 対応ゲーム: ${s.compatibleGames}")
        s.points.forEach { (k, v) -> p.msg("<gray> 地点 <white>$k<gray> × ${v.size}") }
        s.regions.forEach { (k, v) -> p.msg("<gray> 領域 <white>$k<gray> × ${v.size}") }
        check(s).forEach { p.msg("<red> ⚠ $it") }
    }

    /** Requirements check against every compatible game (spec §11). */
    private fun check(s: Session): List<String> {
        val def = toDefinition(s)
        return s.compatibleGames.flatMap { g ->
            val game = core.definitions.game(g)?.compiled ?: return@flatMap listOf("ゲーム $g が読み込まれていません")
            DefinitionStore.problems(game.definition.mapRequirements, def).map { "$g: $it" }
        }
    }

    private fun toDefinition(s: Session) = MapDefinition(
        MapDefinition.FORMAT_VERSION, s.mapId, s.displayName,
        WorldSpec(WorldSpec.SourceType.TEMPLATE, s.templateRef, null, true, "void", true, ResetMode.RECREATE, emptyList()),
        s.compatibleGames, s.points, s.regions,
    )

    fun save(p: Player) {
        val s = sessions[p.uniqueId] ?: return notEditing(p)
        val problems = check(s)
        val json = toJson(s)
        val wp = core.capabilities.get(WorldProvider::class.java) as? PaperWorldProvider
        val world = s.arena.world
        core.capabilities.execution.handoff(core.plugin, world) {
            world.save()
            val src = world.worldPath
            core.outbox.submit {
                Files.writeString(core.definitions.mapsDir.resolve("${s.mapId}.jmap.json"), json)
                if (wp != null) {
                    val dst = wp.templateFolder(s.templateRef)
                    WorldFiles.delete(dst)
                    WorldFiles.copy(src, dst)
                    Files.deleteIfExists(dst.resolve(WorldFiles.META_FILE))
                }
                core.definitions.reload()
                p.msg("<green>${s.mapId} を保存しました" + if (wp == null) "（テンプレートの保存は現在の world Provider では未対応です）" else "")
                problems.forEach { p.msg("<yellow> ⚠ $it") }
            }
        }
    }

    fun exit(p: Player) {
        val s = sessions.remove(p.uniqueId) ?: return notEditing(p)
        close(p, s)
    }

    private fun close(p: Player, s: Session) {
        if (p.isOnline) {
            s.snapshot.restoreState(p)
            p.teleportAsync(s.snapshot.location() ?: core.lobby()).thenRun {
                core.snapshots.delete(p.uniqueId)
                core.capabilities.get(WorldProvider::class.java)?.dispose(s.arena)
            }
        } else {
            core.capabilities.get(WorldProvider::class.java)?.dispose(s.arena)
        }
    }

    fun shutdown() {
        sessions.forEach { (u, s) -> org.bukkit.Bukkit.getPlayer(u)?.let { s.snapshot.restoreState(it); it.teleport(s.snapshot.location() ?: core.lobby()); core.snapshots.delete(u) } }
        sessions.clear()
    }

    private fun toJson(s: Session): String {
        val o = JsonObject()
        o.addProperty("format", MapDefinition.FORMAT)
        o.addProperty("formatVersion", MapDefinition.FORMAT_VERSION)
        o.addProperty("id", s.mapId)
        o.addProperty("displayName", s.displayName)
        o.add("world", JsonObject().apply {
            add("source", JsonObject().apply {
                addProperty("type", "template")
                addProperty("ref", s.templateRef)
            })
            addProperty("ifMissing", "create")
            add("generator", JsonObject().apply { addProperty("type", "void") })
            addProperty("lifecycle", "ephemeral")
            addProperty("reset", "recreate")
        })
        o.add("compatibleGames", JsonArray().apply { s.compatibleGames.forEach { add(it) } })
        o.add("points", JsonObject().apply {
            s.points.forEach { (k, v) ->
                add(k, JsonArray().apply {
                    v.forEach { pt ->
                        add(JsonObject().apply {
                            addProperty("x", pt.x); addProperty("y", pt.y); addProperty("z", pt.z)
                            addProperty("yaw", pt.yaw); addProperty("pitch", pt.pitch)
                        })
                    }
                })
            }
        })
        o.add("regions", JsonObject().apply {
            s.regions.forEach { (k, v) ->
                add(k, JsonArray().apply {
                    v.forEach { b ->
                        add(JsonObject().apply {
                            add("min", JsonArray().apply { add(b.minX); add(b.minY); add(b.minZ) })
                            add("max", JsonArray().apply { add(b.maxX); add(b.maxY); add(b.maxZ) })
                        })
                    }
                })
            }
        })
        return GsonBuilder().setPrettyPrinting().create().toJson(o)
    }

    // ---------------------------------------------------------------- wand + particles

    fun wand(): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
        editMeta {
            it.displayName(Component.text("JunkGames マップワンド", NamedTextColor.GOLD))
            it.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1)
        }
    }

    @EventHandler
    fun onWand(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        val item = e.item ?: return
        if (item.persistentDataContainer.get(wandKey, PersistentDataType.BYTE) == null) return
        val s = sessions[e.player.uniqueId] ?: return
        val block = e.clickedBlock ?: return
        e.isCancelled = true
        when (e.action) {
            Action.LEFT_CLICK_BLOCK -> s.pos1 = block.location.also { e.player.msg("<aqua>pos1: ${it.blockX}, ${it.blockY}, ${it.blockZ}") }
            Action.RIGHT_CLICK_BLOCK -> s.pos2 = block.location.also { e.player.msg("<aqua>pos2: ${it.blockX}, ${it.blockY}, ${it.blockZ}") }
            else -> {}
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        // snapshot stays on disk and is restored on next login
        sessions.remove(e.player.uniqueId)?.let { core.capabilities.get(WorldProvider::class.java)?.dispose(it.arena) }
    }

    /** Called every 10 ticks: particles for registered points and region outlines. */
    fun renderParticles() {
        for ((u, s) in sessions) {
            val p = org.bukkit.Bukkit.getPlayer(u) ?: continue
            if (p.world != s.arena.world) continue
            core.capabilities.execution.handoff(core.plugin, s.arena.world) {
                s.points.values.flatten().forEach { pt -> p.spawnParticle(Particle.HAPPY_VILLAGER, pt.x, pt.y + 0.2, pt.z, 3, 0.1, 0.1, 0.1, 0.0) }
                var budget = 600
                for (b in s.regions.values.flatten()) {
                    val step = maxOf(1, ((b.maxX - b.minX) + (b.maxY - b.minY) + (b.maxZ - b.minZ)) / 40)
                    for (x in b.minX..b.maxX + 1 step step) for (y in listOf(b.minY, b.maxY + 1)) for (z in listOf(b.minZ, b.maxZ + 1)) {
                        if (budget-- <= 0) return@handoff
                        p.spawnParticle(Particle.END_ROD, x.toDouble(), y.toDouble(), z.toDouble(), 1, 0.0, 0.0, 0.0, 0.0)
                    }
                    for (z in b.minZ..b.maxZ + 1 step step) for (y in listOf(b.minY, b.maxY + 1)) for (x in listOf(b.minX, b.maxX + 1)) {
                        if (budget-- <= 0) return@handoff
                        p.spawnParticle(Particle.END_ROD, x.toDouble(), y.toDouble(), z.toDouble(), 1, 0.0, 0.0, 0.0, 0.0)
                    }
                }
            }
        }
    }

    private fun notEditing(p: Player) = p.msg("<red>マップを編集していません。/jgmap edit <マップID>")

    private fun Player.msg(m: String) = sendMessage(mm.deserialize("<dark_aqua>[JG Map]</dark_aqua> $m"))
}
