package net.azisaba.jg.core.instance

import net.azisaba.jg.api.ir.GameMeta
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.ir.MapDefinition
import net.azisaba.jg.api.runtime.BroadcastHandle
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.GameContext
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.GameState
import net.azisaba.jg.api.runtime.GameTeam
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.api.runtime.Region
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.Core
import net.azisaba.jg.core.compile.CompiledGame
import net.azisaba.jg.core.compile.Literals
import net.azisaba.jg.core.runtime.InstanceRuntime
import net.azisaba.jg.core.runtime.RuntimeListener
import net.azisaba.jg.core.util.ItemParser
import net.azisaba.jg.platform.ArenaWorld
import net.azisaba.jg.platform.Provider
import net.azisaba.jg.platform.ResetMode
import net.azisaba.jg.platform.StructureProvider
import net.azisaba.jg.platform.TickHandle
import net.azisaba.jg.platform.ViewHandle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.util.ArrayDeque
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

/**
 * One match (spec §3 GameInstance). Owns its world, players, variables and script runtime.
 *
 * Threading (spec §12.8 conventions 1 & 3): [tick] runs on the thread that ticks the arena world,
 * and all mutable state is touched only there. Other threads talk to the instance through
 * [submit] (drained at the start of each tick) or read volatile / concurrent fields.
 */
class GameInstance internal constructor(
    private val core: Core,
    val id: String,
    val compiled: CompiledGame,
    val map: MapDefinition,
) : GameContext {
    val meta: GameMeta = compiled.definition.meta
    override val limits: Limits = core.settings.limits
    override val random = Random()
    val runtime = InstanceRuntime(compiled, this, limits, Listener())
    private val ui = InstanceUi()

    @Volatile override var state = GameState.IDLE
        private set

    @Volatile var arena: ArenaWorld? = null
        private set
    private var tickHandle: TickHandle? = null
    private var snapshot: StructureProvider.Snapshot? = null

    private val players = ConcurrentHashMap<UUID, GamePlayerImpl>()
    private val order = CopyOnWriteArrayList<GamePlayerImpl>()
    private val reserved: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val reservations = AtomicInteger()
    private val teams = meta.teams.list.map { GameTeamImpl(it.id, it.name, NamedTextColor.NAMES.value(it.color) ?: NamedTextColor.WHITE) }
    private val inbox = ConcurrentLinkedQueue<() -> Unit>()

    private val instanceVars = HashMap<String, Any?>()
    private val playerVars = HashMap<UUID, MutableMap<String, Any?>>()
    private val teamVars = HashMap<String, MutableMap<String, Any?>>()
    private val defaults = HashMap<String, Any?>()

    private val entities = ArrayList<Entity>()
    private val views = HashMap<UUID, MutableList<ViewHandle>>()
    private val errorTicks = ArrayDeque<Long>()
    private val debugWatchers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override var tick = 0L
        private set
    private var countdown = 0
    private var timeLeftTicks = 0
    private var endingTicks = 0
    private var spawnCursor = 0
    private var invalidated = false

    // ================================================================== thread-safe surface

    fun submit(task: () -> Unit) {
        inbox += task
    }

    /** Players inside plus players on their way in. */
    fun occupancy(): Int = players.values.count { !it.left } + reservations.get()

    fun acceptsJoin(): Boolean {
        val s = state
        val open = s == GameState.IDLE || s == GameState.WAITING || s == GameState.COUNTDOWN ||
            (s == GameState.RUNNING && meta.allowLateJoin)
        return open && !invalidated && occupancy() < meta.maxPlayers
    }

    fun contains(uuid: UUID): Boolean = participant(uuid) != null || uuid in reserved

    fun participant(uuid: UUID): GamePlayerImpl? = players[uuid]?.takeIf { !it.left }

    fun isKeptDisconnected(uuid: UUID): Boolean = players[uuid]?.let { !it.left && it.disconnected } ?: false

    fun toggleDebug(uuid: UUID): Boolean = if (debugWatchers.add(uuid)) true else {
        debugWatchers.remove(uuid)
        false
    }

    // ================================================================== world lifecycle

    /** Called once the world is ready (any thread). */
    internal fun onWorldReady(arena: ArenaWorld) {
        this.arena = arena
        compiled.definition.variables.forEach { defaults[it.name] = defaultValue(it) }
        if (arena.spec.reset == ResetMode.SNAPSHOT) {
            core.capabilities.get(StructureProvider::class.java)?.snapshot(arena.world, arena.spec.snapshotBoxes)?.whenComplete { s, e ->
                if (e != null) core.plugin.logger.log(Level.WARNING, "スナップショットを取れませんでした: $id", e)
                else submit { snapshot = s }
            }
        }
        tickHandle = core.capabilities.execution.scheduleWorldTick(core.plugin, arena.world) { tick() }
        submit { enterWaiting() }
    }

    internal fun reserve(uuid: UUID) {
        if (reserved.add(uuid)) reservations.incrementAndGet()
    }

    internal fun unreserve(uuid: UUID) {
        if (reserved.remove(uuid)) reservations.decrementAndGet()
    }

    /** Location for arriving players (lobby point, or a spawn). */
    internal fun arrivalLocation(): Location {
        for (name in listOf("lobby", "waiting", "spawn")) {
            val pts = points(name)
            if (pts.isNotEmpty()) return pts[random.nextInt(pts.size)]
        }
        return world.spawnLocation
    }

    // ================================================================== tick

    private fun tick() {
        try {
            var n = 0
            while (n++ < 1000) (inbox.poll() ?: break).invoke()
            tick++
            when (state) {
                GameState.WAITING -> if (activePlayers() >= meta.minPlayers) enterCountdown() else if (tick % 40 == 0L) waitingHint()
                GameState.COUNTDOWN -> tickCountdown()
                GameState.RUNNING -> {
                    if (meta.timeLimitSeconds > 0 && --timeLeftTicks <= 0) {
                        runtime.fireSync(TriggerEvent("time_up", false))
                        if (state == GameState.RUNNING) endGame(null)
                    }
                    if (state == GameState.RUNNING && activePlayers() == 0) endGame(null)
                }
                GameState.ENDING -> if (--endingTicks <= 0) reset()
                else -> {}
            }
            if (state != GameState.IDLE && state != GameState.RESET) runtime.runTick()
            if (tick % 20 == 0L) entities.removeIf { !it.isValid }
        } catch (t: Throwable) {
            // a bug in the core, not in the game: never let it kill the world thread (§12.8 convention 7)
            core.plugin.logger.log(Level.SEVERE, "[$id] コアの内部エラーのため試合を中断します", t)
            if (state != GameState.RESET && state != GameState.IDLE) {
                invalidated = true
                runCatching { reset() }.onFailure { core.plugin.logger.log(Level.SEVERE, "[$id] 後始末にも失敗しました", it) }
            }
        }
    }

    private fun activePlayers() = order.count { !it.left && !it.disconnected }

    private fun online(): List<Player> = order.mapNotNull { it.player }

    private fun enterWaiting() {
        state = GameState.WAITING
        runtime.fireSync(TriggerEvent("state_waiting", false))
    }

    private fun waitingHint() {
        val c = MM.deserialize("<gray>参加者を待っています… <white>${activePlayers()}<gray>/${meta.minPlayers}")
        online().forEach { it.sendActionBar(c) }
    }

    private fun enterCountdown() {
        state = GameState.COUNTDOWN
        countdown = meta.countdownSeconds * 20
        runtime.fireSync(TriggerEvent("countdown", false))
    }

    private fun tickCountdown() {
        if (activePlayers() < (if (forced) 1 else meta.minPlayers)) {
            forced = false
            announce("<red>人数が足りなくなったため、カウントダウンを中止しました")
            enterWaiting()
            return
        }
        if (countdown % 20 == 0) {
            val sec = countdown / 20
            for (p in online()) {
                p.sendActionBar(MM.deserialize("<yellow>${meta.displayName} <white>開始まで <gold>$sec<white> 秒"))
                if (sec in 1..5) {
                    p.showTitle(Title.title(Component.text(sec, NamedTextColor.GOLD), Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))))
                    p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f)
                }
            }
        }
        if (--countdown <= 0) startGame()
    }

    private fun playMode() = if (meta.rules.blockBreak || meta.rules.blockPlace) GameMode.SURVIVAL else GameMode.ADVENTURE

    private fun startGame() {
        state = GameState.RUNNING
        timeLeftTicks = meta.timeLimitSeconds * 20
        if (teams.isNotEmpty()) {
            order.filter { !it.left }.shuffled(random).forEach { assignTeam(it) }
            ui.syncTeams(teams)
        }
        for (gp in order) {
            val p = gp.player ?: continue
            PlayerSnapshot.reset(p, playMode())
            p.teleportAsync(spawnFor(gp))
            p.showTitle(Title.title(MM.deserialize("<green>スタート！"), MM.deserialize(meta.displayName), Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))))
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        }
        runtime.fireSync(TriggerEvent("game_start", false))
    }

    private fun assignTeam(p: GamePlayerImpl) {
        if (teams.isEmpty() || p.teamImpl != null) return
        val smallest = teams.minBy { it.memberList.size }
        smallest.memberList += p
        p.teamImpl = smallest
    }

    /** Team spawns (`spawn_<team>`) take precedence over shared `spawn` points. */
    internal fun spawnFor(p: GamePlayerImpl): Location {
        p.teamImpl?.let { t -> points("spawn_${t.id}").takeIf { it.isNotEmpty() }?.let { return it[spawnCursor++ % it.size] } }
        points("spawn").takeIf { it.isNotEmpty() }?.let { return it[spawnCursor++ % it.size] }
        return arrivalLocation()
    }

    // ================================================================== players

    /** Instance thread: a player arrived (after the coordinator teleported them). */
    internal fun addPlayer(p: Player, snapshot: PlayerSnapshot) {
        unreserve(p.uniqueId)
        if (state == GameState.RESET || state == GameState.IDLE || invalidated) {
            core.instances.returnToLobby(p, snapshot)
            return
        }
        val gp = GamePlayerImpl(p, snapshot)
        players[gp.uuid] = gp
        order += gp
        ui.attach(p)
        if (state == GameState.RUNNING) {
            assignTeam(gp)
            PlayerSnapshot.reset(p, playMode())
            p.teleportAsync(spawnFor(gp))
        } else {
            PlayerSnapshot.reset(p, GameMode.ADVENTURE)
        }
        if (teams.isNotEmpty()) ui.syncTeams(teams)
        announce("<gray>${MM.escapeTags(p.name)} <white>が参加しました <gray>(${activePlayers()}/${meta.maxPlayers})")
        runtime.fireSync(TriggerEvent("player_join", false).player(gp))
    }

    /** Instance thread: rejoin of a kept (disconnected) player. */
    internal fun rejoin(p: Player) {
        val gp = players[p.uniqueId]?.takeIf { it.disconnected } ?: return
        gp.handle = p
        gp.disconnected = false
        ui.attach(p)
        if (teams.isNotEmpty()) ui.syncTeams(teams)
        if (!gp.alive) p.gameMode = GameMode.SPECTATOR
        announce("<gray>${MM.escapeTags(p.name)} <white>が復帰しました")
    }

    enum class LeaveReason { COMMAND, QUIT, ELIMINATED, GAME_END, WORLD_CHANGE }

    /** Instance thread. Restores the player and sends them back (if online). */
    internal fun removePlayer(uuid: UUID, reason: LeaveReason): CompletableFuture<*> {
        val gp = players[uuid]?.takeIf { !it.left } ?: return CompletableFuture.completedFuture(null)
        val p = gp.handle
        val online = p != null && p.isOnline

        if (reason == LeaveReason.QUIT && meta.onDisconnect == GameMeta.OnDisconnect.KEEP && state == GameState.RUNNING) {
            gp.disconnected = true // keeps slot, team and variables; the snapshot stays on disk
            runtime.fireSync(TriggerEvent("player_quit", false).player(gp))
            return CompletableFuture.completedFuture(null)
        }
        runtime.fireSync(TriggerEvent("player_quit", false).player(gp))
        gp.left = true
        gp.alive = false
        runtime.stopPlayerRunners(uuid)
        closeViews(uuid)
        core.instances.forget(uuid, this)
        if (state != GameState.RESET) announce("<gray>${MM.escapeTags(gp.name)} <white>が退出しました")
        // offline: restored on next login from the persisted snapshot
        if (!online || reason == LeaveReason.QUIT) return CompletableFuture.completedFuture(null)
        ui.detach(p)
        if (reason == LeaveReason.WORLD_CHANGE) {
            core.instances.restoreInPlace(p, gp.snapshot)
            return CompletableFuture.completedFuture(null)
        }
        return core.instances.returnToLobby(p, gp.snapshot)
    }

    /** The player left the arena world by other means (another plugin teleported them). */
    internal fun removePlayerExternal(uuid: UUID) {
        removePlayer(uuid, LeaveReason.WORLD_CHANGE)
    }

    // ================================================================== ending

    override fun endGame(winner: Any?) {
        if (state != GameState.RUNNING) return
        state = GameState.ENDING
        endingTicks = maxOf(1, meta.endingSeconds) * 20
        runtime.stopAll() // gameplay scripts stop; game_end handlers run during ENDING
        runtime.post(TriggerEvent("game_end", false).value(winner))
        val text = when (winner) {
            is GamePlayer -> "<gold>勝者: <yellow>${MM.escapeTags(winner.name)}"
            is GameTeam -> "<gold>勝者: <${winner.color}>${winner.displayName}"
            else -> if (invalidated) "<red>試合は無効になりました" else "<gray>勝者なし"
        }
        for (p in online()) {
            p.showTitle(Title.title(MM.deserialize("<yellow>ゲーム終了"), MM.deserialize(text)))
            p.playSound(p.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }
        announce(text)
    }

    /** Ends without a winner because of too many errors (spec §7.5). */
    private fun invalidate(reason: String) {
        if (invalidated) return
        invalidated = true
        announce("<red>$reason")
        when (state) {
            GameState.RUNNING -> endGame(null)
            GameState.WAITING, GameState.COUNTDOWN -> reset()
            else -> {}
        }
    }

    /** Admin stop. */
    fun forceStop() = submit {
        when (state) {
            GameState.RUNNING -> endGame(null)
            GameState.RESET, GameState.IDLE -> {}
            else -> reset()
        }
    }

    /** Admin start: skip waiting for players. */
    fun forceStart() = submit {
        if ((state == GameState.WAITING || state == GameState.COUNTDOWN) && activePlayers() > 0) {
            countdown = if (state == GameState.WAITING) 5 * 20 else minOf(countdown, 5 * 20)
            state = GameState.COUNTDOWN
            forced = true
        }
    }

    /** Admin-started: the countdown is not cancelled for lack of players. */
    private var forced = false

    private fun reset() {
        state = GameState.RESET
        runtime.stopAll()
        views.keys.toList().forEach { closeViews(it) }
        entities.filter { it.isValid }.forEach { it.remove() }
        entities.clear()
        val moves = order.toList().map { removePlayer(it.uuid, LeaveReason.GAME_END) }
        reserved.toList().forEach { unreserve(it) }
        CompletableFuture.allOf(*moves.toTypedArray()).orTimeout(10, TimeUnit.SECONDS)
            .whenComplete { _, _ -> submit { dispose() } }
    }

    private fun dispose() {
        tickHandle?.close()
        tickHandle = null
        val a = arena
        val snap = snapshot
        if (a != null && snap != null && a.spec.reset == ResetMode.SNAPSHOT) {
            core.capabilities.get(StructureProvider::class.java)?.restore(a.world, snap, limits.maxFillBlocksPerTick)
        }
        state = GameState.IDLE
        core.instances.closed(this)
    }

    /** Server shutdown: synchronous best-effort restore. */
    internal fun shutdown() {
        runtime.stopAll()
        views.keys.toList().forEach { closeViews(it) }
        for (gp in order) {
            if (gp.left) continue
            gp.left = true
            val p = gp.handle ?: continue
            if (!p.isOnline) continue
            ui.detach(p)
            gp.snapshot.restoreState(p)
            val to = gp.snapshot.location()
            p.teleport(if (to != null && to.world != arena?.world) to else core.lobby())
            core.snapshots.delete(p.uniqueId)
        }
        tickHandle?.close()
        state = GameState.IDLE
    }

    // ================================================================== events from listeners

    /** Listener entry: fire an event synchronously (instance thread). */
    fun fire(e: TriggerEvent): TriggerEvent = runtime.fireSync(e)

    fun hasScripts(eventType: String) = compiled.scriptsFor(eventType).isNotEmpty()

    /** Region enter/exit and move-onto-block tracking on block-position change. */
    fun onMove(gp: GamePlayerImpl, to: Location, event: Cancellable) {
        if (hasScripts("region_enter") || hasScripts("region_exit")) {
            for (name in map.regions.keys) {
                val r = region(name) ?: continue
                val inside = r.contains(to)
                if (inside == name in gp.regions) continue
                val te = fire(TriggerEvent(if (inside) "region_enter" else "region_exit", true).player(gp).key(name))
                if (te.cancelled) {
                    event.isCancelled = true
                    return
                }
                if (inside) gp.regions += name else gp.regions -= name
            }
        }
        if (hasScripts("move_onto_block")) {
            val bx = to.blockX
            val by = to.blockY - 1
            val bz = to.blockZ
            if (bx != gp.lastBlockX || by != gp.lastBlockY || bz != gp.lastBlockZ) {
                gp.lastBlockX = bx
                gp.lastBlockY = by
                gp.lastBlockZ = bz
                val below = to.world.getBlockAt(bx, by, bz)
                if (below.type.isSolid) {
                    val te = fire(TriggerEvent("move_onto_block", true).player(gp).blockLocation(below.location).material(below.type))
                    if (te.cancelled) event.isCancelled = true
                }
            }
        }
    }

    fun onDeath(victim: GamePlayerImpl, killer: GamePlayerImpl?) {
        fire(TriggerEvent("player_death", false).player(victim).otherPlayer(killer))
        if (killer != null) fire(TriggerEvent("player_kill", false).player(killer).otherPlayer(victim))
    }

    fun respawnLocation(gp: GamePlayerImpl): Location = if (gp.alive) spawnFor(gp) else arrivalLocation()

    fun onRespawned(gp: GamePlayerImpl) {
        val p = gp.player ?: return
        if (!gp.alive || gp.spectator) p.gameMode = GameMode.SPECTATOR
        runtime.post(TriggerEvent("player_respawn", false).player(gp))
    }

    // ================================================================== GameContext

    override val instanceId: String get() = id
    override val gameId: String get() = compiled.id
    override val gameVersion: Int get() = compiled.version
    override val mapId: String get() = map.id
    override val world: World get() = arena?.world ?: error("world not ready")

    override val timeLeftSeconds: Int
        get() = when {
            meta.timeLimitSeconds <= 0 -> -1
            state == GameState.RUNNING -> maxOf(0, (timeLeftTicks + 19) / 20)
            state == GameState.ENDING -> 0
            else -> meta.timeLimitSeconds
        }

    override fun players(): List<GamePlayer> = order.filter { !it.left }
    override fun alivePlayers(): List<GamePlayer> = order.filter { !it.left && it.alive && !it.spectator }
    override fun wrap(player: Player): GamePlayer? = participant(player.uniqueId)
    override fun teams(): List<GameTeam> = teams.toList()

    override fun points(name: String): List<Location> {
        val pts = map.points[name] ?: return emptyList()
        val w = arena?.world ?: return emptyList()
        return pts.map { Location(w, it.x, it.y, it.z, it.yaw, it.pitch) }
    }

    override fun region(name: String?): Region? {
        if (name == null) return null
        val w = arena?.world ?: return null
        return map.regions[name]?.let { Region(name, w, it) }
    }

    override fun eliminate(player: GamePlayer) {
        val gp = players[player.uuid]?.takeIf { !it.left && it.alive } ?: return
        gp.alive = false
        if (meta.spectators) {
            gp.spectator = true
            gp.player?.let {
                it.gameMode = GameMode.SPECTATOR
                it.showTitle(Title.title(MM.deserialize("<red>脱落"), MM.deserialize("<gray>観戦モードになりました")))
            }
        } else {
            submit { removePlayer(gp.uuid, LeaveReason.ELIMINATED) } // not while a runner is executing
        }
        announce("<red>${MM.escapeTags(gp.name)} <gray>が脱落しました（残り ${alivePlayers().size} 人）")
    }

    override fun makeSpectator(player: GamePlayer) {
        val gp = participant(player.uuid) ?: return
        gp.spectator = true
        gp.player?.gameMode = GameMode.SPECTATOR
    }

    override fun stopAll() = runtime.stopAll()

    override fun broadcast(message: String, value: Any?): BroadcastHandle = runtime.broadcast(message, value)

    override fun getVariable(name: String, target: Any?): Any? {
        val v = compiled.variables[name] ?: throw ScriptError("変数 '$name' は宣言されていません")
        val store = store(v, target, false)
        return if (store == null || name !in store) Values.copyIfList(defaults[name]) else store[name]
    }

    override fun setVariable(name: String, target: Any?, value: Any?) {
        val v = compiled.variables[name] ?: throw ScriptError("変数 '$name' は宣言されていません")
        val store = store(v, target, true) ?: return
        val old = if (name in store) store[name] else defaults[name]
        store[name] = value
        if (!Values.equal(old, value) && hasScripts("variable_changed")) {
            runtime.post(TriggerEvent("variable_changed", false).key(name).value(Values.copyIfList(value)).player(target as? GamePlayer))
        }
    }

    private fun store(v: IrVariable, target: Any?, create: Boolean): MutableMap<String, Any?>? = when (v.scope) {
        IrVariable.Scope.INSTANCE -> instanceVars
        IrVariable.Scope.PLAYER -> (target as? GamePlayer)?.let { if (create) playerVars.getOrPut(it.uuid) { HashMap() } else playerVars[it.uuid] }
        IrVariable.Scope.TEAM -> (target as? GameTeam)?.let { if (create) teamVars.getOrPut(it.id) { HashMap() } else teamVars[it.id] }
    }

    private fun defaultValue(v: IrVariable): Any? {
        val d = v.defaultValue
        if (d == null || d.isJsonNull) {
            return when (v.type.kind) {
                ValueType.Kind.NUMBER -> 0.0
                ValueType.Kind.STRING -> ""
                ValueType.Kind.BOOLEAN -> false
                ValueType.Kind.LIST -> ArrayList<Any?>()
                else -> null
            }
        }
        return try {
            Literals.compile(d, v.type, compiled.definition.mapRequirements).eval(ConstantExec(this))
        } catch (e: Exception) {
            core.plugin.logger.warning("[$id] 変数 ${v.name} の既定値を評価できません: ${e.message}")
            null
        }
    }

    override fun spawnTracked(location: Location, type: EntityType): Entity? {
        entities.removeIf { !it.isValid }
        if (entities.size >= limits.maxEntities) throw ScriptError("召喚エンティティ数が上限（${limits.maxEntities}）に達しました")
        val loc = location.clone().also { it.world = world }
        return world.spawnEntity(loc, type).also {
            it.isPersistent = false
            entities += it
        }
    }

    override fun trackView(owner: GamePlayer, handle: ViewHandle) {
        views.getOrPut(owner.uuid) { ArrayList() } += handle
    }

    private fun closeViews(uuid: UUID) {
        views.remove(uuid)?.forEach { h ->
            runCatching { h.close() }.onFailure { core.plugin.logger.log(Level.WARNING, "ViewHandle の解除に失敗", it) }
        }
    }

    override fun <T : Provider> provider(type: Class<T>): T? = core.capabilities.get(type)

    override fun kit(name: String): List<ItemStack> = meta.kits[name].orEmpty().mapNotNull { e ->
        try {
            ItemParser.parse(e)
        } catch (ex: IllegalArgumentException) {
            warn("キット $name のアイテムが不正です: ${ex.message}")
            null
        }
    }

    override fun setSidebarTitle(target: GamePlayer?, miniMessage: String) {
        if (target != null && target.player == null) return
        ui.setTitle(target?.player, miniMessage, online())
    }

    override fun setSidebarLine(target: GamePlayer?, line: Int, miniMessage: String?) {
        if (target != null && target.player == null) return
        ui.setLine(target?.player, line, miniMessage, online())
    }

    override fun showBossBar(id: String, target: GamePlayer?, miniMessage: String, progress: Float, color: String) {
        if (target != null && target.player == null) return
        ui.showBar(id, target?.player, miniMessage, progress, color, online())
    }

    override fun hideBossBar(id: String, target: GamePlayer?) {
        if (target != null && target.player == null) return
        ui.hideBar(id, target?.player, online())
    }

    override fun announce(miniMessage: String) {
        val c = MM.deserialize(miniMessage)
        online().forEach { it.sendMessage(c) }
    }

    override fun warn(message: String) = core.outbox.scriptError(id, compiled.id, compiled.version, "-", null, message, true)

    // ================================================================== runtime diagnostics

    private inner class Listener : RuntimeListener {
        override fun runtimeError(scriptId: String, nodeId: String?, message: String) {
            core.outbox.scriptError(id, compiled.id, compiled.version, scriptId, nodeId, message, false)
            notifyWatchers("<red>[エラー] <gray>$scriptId${nodeId?.let { " [$it]" } ?: ""} <white>${MM.escapeTags(message)}")
            errorTicks.addLast(tick)
            while (errorTicks.isNotEmpty() && errorTicks.peekFirst() < tick - 20 * 60) errorTicks.removeFirst()
            if (errorTicks.size > limits.errorThresholdPerMinute) {
                submit { invalidate("実行時エラーが多すぎるため、試合を無効にしました") }
            }
        }

        override fun runtimeWarning(scriptId: String, nodeId: String?, message: String) {
            core.outbox.scriptError(id, compiled.id, compiled.version, scriptId, nodeId, message, true)
            notifyWatchers("<yellow>[警告] <gray>$scriptId${nodeId?.let { " [$it]" } ?: ""} <white>${MM.escapeTags(message)}")
        }

        override fun internalError(scriptId: String, t: Throwable) {
            core.plugin.logger.log(Level.SEVERE, "[$id] ブロック実装の内部エラー ($scriptId)", t)
        }
    }

    private fun notifyWatchers(mm: String) {
        val c = MM.deserialize("<dark_gray>[$id] ").append(MM.deserialize(mm))
        for (p in online()) {
            if (p.uniqueId in debugWatchers || (core.settings.debugMessages && p.hasPermission("junkgames.debug"))) p.sendMessage(c)
        }
    }

    /** Minimal Exec for evaluating constant default values. */
    private class ConstantExec(override val game: GameContext) : Exec {
        override fun eval(expr: Expr): Any? = expr.eval(this)
        override fun push(stmt: Stmt) = throw UnsupportedOperationException()
        override fun pushCall(body: Stmt, locals: MutableMap<String, Any?>) = throw UnsupportedOperationException()
        override fun returnFromCall(value: Any?) {}
        override val callDepth = 0
        override fun getLocal(name: String): Any? = null
        override fun hasLocal(name: String) = false
        override fun setLocal(name: String, value: Any?) {}
        override val event = TriggerEvent.NONE
        override val tick = 0L
        override fun sleepUntil(tick: Long) {}
        override fun stopScript() {}
        override val inSyncSection = false
        override fun warn(message: String) {}
    }

    private companion object {
        val MM: MiniMessage = MiniMessage.miniMessage()
    }
}
