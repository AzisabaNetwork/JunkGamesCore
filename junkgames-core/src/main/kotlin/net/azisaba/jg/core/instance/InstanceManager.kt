package net.azisaba.jg.core.instance

import net.azisaba.jg.api.ir.MapDefinition
import net.azisaba.jg.api.runtime.GameState
import net.azisaba.jg.core.Core
import net.azisaba.jg.core.compile.CompiledGame
import net.azisaba.jg.platform.ArenaWorld
import net.azisaba.jg.platform.WorldKey
import net.azisaba.jg.platform.WorldProvider
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.logging.Level

/**
 * Lobby, queue and instance registry (spec §8.2), plus the cross-world coordinator
 * (spec §12.8 convention 4): every move between worlds is a non-blocking hand-off to the target
 * world's next tick, never a synchronous wait.
 */
class InstanceManager(private val core: Core) {
    private val instances = ConcurrentHashMap<String, GameInstance>()
    private val byWorld = ConcurrentHashMap<UUID, GameInstance>()
    private val byPlayer = ConcurrentHashMap<UUID, GameInstance>()
    private val queue = ConcurrentLinkedDeque<QueueEntry>()
    private val pool = ConcurrentHashMap<String, ConcurrentLinkedDeque<Pair<String, ArenaWorld>>>()
    private val preparingPool = ConcurrentHashMap<String, Int>()

    private data class QueueEntry(val player: UUID, val game: String, val map: String?, val since: Long)

    private val mm = MiniMessage.miniMessage()

    fun all(): Collection<GameInstance> = instances.values
    fun get(id: String): GameInstance? = instances[id]
    fun byWorld(world: World): GameInstance? = byWorld[world.uid]
    fun byPlayer(uuid: UUID): GameInstance? = byPlayer[uuid]

    private val worldProvider: WorldProvider
        get() = core.capabilities.get(WorldProvider::class.java) ?: error("world 能力がありません")

    // ------------------------------------------------------------------ join

    /** `/play <game> [map]`. Called on the global thread. */
    fun join(p: Player, gameId: String, mapId: String?) {
        if (byPlayer.containsKey(p.uniqueId)) return p.msg("<red>すでにゲームに参加しています。<gray>/leave で退出できます")
        val lg = core.definitions.game(gameId) ?: return p.msg("<red>ゲーム $gameId は見つかりません")
        val game = lg.compiled
        if (game == null || !lg.playable) return p.msg("<red>${lg.id} は現在遊べません: ${lg.disabledReason}")
        val maps = core.definitions.compatibleMaps(game).filter { mapId == null || it.id == mapId }
        if (maps.isEmpty()) return p.msg("<red>${game.definition.meta.displayName} を遊べるマップがありません${mapId?.let { "（$it）" } ?: ""}")

        val target = instances.values
            .filter { it.compiled.id == gameId && (mapId == null || it.map.id == mapId) && it.acceptsJoin() }
            // prefer instances that are about to start, then fuller ones
            .sortedWith(compareBy<GameInstance> { if (it.state == GameState.COUNTDOWN) 0 else 1 }.thenByDescending { it.occupancy() })
            .firstOrNull()
        if (target != null) return enter(p, target)

        if (instances.size >= core.settings.maxInstances) {
            queue.removeIf { it.player == p.uniqueId }
            queue += QueueEntry(p.uniqueId, gameId, mapId, System.currentTimeMillis())
            return p.msg("<yellow>空きアリーナがないため、待ち列に入りました（${queue.size} 番目）")
        }
        val inst = create(game, maps.random()) ?: return p.msg("<red>アリーナを用意できませんでした")
        enter(p, inst)
    }

    private fun create(game: CompiledGame, map: MapDefinition): GameInstance? {
        val id = "${game.id}-" + UUID.randomUUID().toString().take(6)
        val pooled = pool[map.id]?.pollFirst()
        val inst = GameInstance(core, pooled?.first ?: id, game, map)
        instances[inst.id] = inst
        if (pooled != null) {
            ready(inst, pooled.second)
            refillPool(map)
            return inst
        }
        val spec = map.world.copy(gameId = game.id, gameVersion = game.version)
        val key = if (spec.ephemeral) WorldKey.ephemeral(game.id, inst.id) else WorldKey.persistent("maps/${map.id}")
        worldProvider.prepare(spec, key).whenComplete { arena, e ->
            if (e != null) {
                core.plugin.logger.log(Level.WARNING, "アリーナ ${inst.id} を用意できませんでした", e)
                core.capabilities.execution.runGlobal(core.plugin) { failed(inst, e.cause?.message ?: e.message ?: "?") }
            } else ready(inst, arena)
        }
        return inst
    }

    private fun ready(inst: GameInstance, arena: ArenaWorld) {
        byWorld[arena.world.uid] = inst
        inst.onWorldReady(arena)
    }

    private fun failed(inst: GameInstance, reason: String) {
        instances.remove(inst.id)
        byPlayer.entries.removeIf { (uuid, i) ->
            if (i !== inst) return@removeIf false
            Bukkit.getPlayer(uuid)?.msg("<red>アリーナの準備に失敗しました: $reason")
            true
        }
    }

    /**
     * Moves a player into an instance: snapshot + clear on their current world's thread, teleport,
     * then register on the arena's thread. Never blocks.
     */
    private fun enter(p: Player, inst: GameInstance) {
        inst.reserve(p.uniqueId)
        byPlayer[p.uniqueId] = inst
        p.msg("<gray>${inst.meta.displayName} <white>に参加します…")
        whenReady(inst) {
            val exec = core.capabilities.execution
            exec.handoff(core.plugin, p.world) {
                if (!p.isOnline || byPlayer[p.uniqueId] !== inst) {
                    inst.unreserve(p.uniqueId)
                    return@handoff
                }
                val snap = PlayerSnapshot.capture(p)
                core.snapshots.save(snap)
                p.teleportAsync(inst.arrivalLocation()).whenComplete { ok, e ->
                    if (e != null || ok != true) {
                        inst.unreserve(p.uniqueId)
                        byPlayer.remove(p.uniqueId, inst)
                        core.snapshots.delete(p.uniqueId)
                        p.msg("<red>アリーナへ移動できませんでした")
                    } else inst.submit { inst.addPlayer(p, snap) }
                }
            }
        }
    }

    /** Runs [task] (global thread) once the instance's world exists. */
    private fun whenReady(inst: GameInstance, task: () -> Unit) {
        if (inst.arena != null) return core.capabilities.execution.runGlobal(core.plugin, task)
        Bukkit.getScheduler().runTaskTimer(core.plugin, { t ->
            when {
                inst.arena != null -> {
                    t.cancel()
                    core.capabilities.execution.runGlobal(core.plugin, task)
                }
                !instances.containsKey(inst.id) -> t.cancel()
            }
        }, 2L, 2L)
    }

    // ------------------------------------------------------------------ leave

    fun leave(p: Player) {
        val inst = byPlayer[p.uniqueId]
        if (inst == null) {
            if (queue.removeIf { it.player == p.uniqueId }) p.msg("<gray>待ち列から抜けました") else p.msg("<red>ゲームに参加していません")
            return
        }
        inst.submit { inst.removePlayer(p.uniqueId, GameInstance.LeaveReason.COMMAND) }
    }

    /** Instance thread: restore inventory etc. on the arena thread, then hand off to the lobby. */
    internal fun returnToLobby(p: Player, snapshot: PlayerSnapshot): CompletableFuture<*> {
        byPlayer.remove(p.uniqueId)
        if (!p.isOnline) return CompletableFuture.completedFuture(null)
        snapshot.restoreState(p)
        val to = snapshot.location()?.takeIf { byWorld[it.world.uid] == null } ?: core.lobby()
        return p.teleportAsync(to).whenComplete { ok, _ -> if (ok == true) core.snapshots.delete(p.uniqueId) }
    }

    /** Restores state (not location) on the thread of the world the player is in now. */
    internal fun restoreInPlace(p: Player, snapshot: PlayerSnapshot) {
        byPlayer.remove(p.uniqueId)
        core.capabilities.execution.handoff(core.plugin, p.world) {
            if (!p.isOnline) return@handoff
            snapshot.restoreState(p)
            core.snapshots.delete(p.uniqueId)
        }
    }

    internal fun forget(uuid: UUID, inst: GameInstance) {
        byPlayer.remove(uuid, inst)
    }

    /** Called by an instance after reset (its world is no longer in use). */
    internal fun closed(inst: GameInstance) {
        instances.remove(inst.id)
        val arena = inst.arena
        if (arena != null) {
            byWorld.remove(arena.world.uid)
            worldProvider.dispose(arena).exceptionally { e ->
                core.plugin.logger.log(Level.WARNING, "ワールドを破棄できませんでした: ${arena.key}", e)
                null
            }
        }
        core.capabilities.execution.runGlobal(core.plugin) { drainQueue() }
    }

    private fun drainQueue() {
        while (instances.size < core.settings.maxInstances) {
            val e = queue.pollFirst() ?: return
            val p = Bukkit.getPlayer(e.player) ?: continue
            join(p, e.game, e.map)
        }
    }

    // ------------------------------------------------------------------ connection events

    /** PlayerQuitEvent (player's world thread). */
    fun onQuit(p: Player) {
        queue.removeIf { it.player == p.uniqueId }
        val inst = byPlayer[p.uniqueId] ?: return
        inst.submit { inst.removePlayer(p.uniqueId, GameInstance.LeaveReason.QUIT) }
    }

    /**
     * PlayerJoinEvent: rejoin a kept match, or restore a snapshot left by a crash / quit.
     * @return true when the player was handled (and will be moved) here
     */
    fun onJoin(p: Player): Boolean {
        val kept = instances.values.firstOrNull { it.isKeptDisconnected(p.uniqueId) }
        if (kept != null) {
            byPlayer[p.uniqueId] = kept
            kept.submit { kept.rejoin(p) }
            return true
        }
        val snap = core.snapshots.takePreloaded(p.uniqueId) ?: return false
        core.capabilities.execution.handoff(core.plugin, p.world) {
            snap.restoreState(p)
            p.teleportAsync(snap.location() ?: core.lobby())
            core.snapshots.delete(p.uniqueId)
            p.msg("<gray>前回の試合から持ち物を復元しました")
        }
        return true
    }

    // ------------------------------------------------------------------ warm pool (spec §8.1)

    fun refillPool(map: MapDefinition) {
        val want = core.settings.warmPool[map.id] ?: return
        if (!map.world.ephemeral) return
        val have = (pool[map.id]?.size ?: 0) + (preparingPool[map.id] ?: 0)
        repeat(want - have) {
            preparingPool.merge(map.id, 1, Int::plus)
            val id = "pool-" + UUID.randomUUID().toString().take(6)
            val game = map.compatibleGames.firstOrNull() ?: "pool"
            worldProvider.prepare(map.world, WorldKey.ephemeral(game, id)).whenComplete { arena, e ->
                preparingPool.merge(map.id, -1, Int::plus)
                if (e != null) core.plugin.logger.log(Level.WARNING, "ウォームプールの準備に失敗: ${map.id}", e)
                else pool.getOrPut(map.id) { ConcurrentLinkedDeque() } += id to arena
            }
        }
    }

    fun shutdown() {
        instances.values.forEach { runCatching { it.shutdown() }.onFailure { e -> core.plugin.logger.log(Level.SEVERE, "shutdown ${it.id}", e) } }
        val provider = core.capabilities.get(WorldProvider::class.java)
        for (inst in instances.values) inst.arena?.let { provider?.dispose(it) }
        pool.values.flatten().forEach { provider?.dispose(it.second) }
        instances.clear()
        byWorld.clear()
        byPlayer.clear()
    }

    private fun Player.msg(m: String) = sendMessage(mm.deserialize("<dark_aqua>[JG]</dark_aqua> $m"))
}
