package net.azisaba.jg.api.runtime

import net.azisaba.jg.platform.BlockBox
import net.azisaba.jg.platform.Provider
import net.azisaba.jg.platform.ViewHandle
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Random
import java.util.UUID

/** Execution context of one runner, handed to every node. */
interface Exec {
    /** Evaluates an expression, counting one step. */
    fun eval(expr: Expr): Any?

    /** Pushes a child frame. The current node must then return [Signal.PUSHED]. */
    fun push(stmt: Stmt)

    /** Pushes a custom-block call frame with its own locals. */
    fun pushCall(body: Stmt, locals: MutableMap<String, Any?>)

    /** Unwinds to the nearest call frame (custom block "return"). */
    fun returnFromCall(value: Any?)

    val callDepth: Int

    fun getLocal(name: String): Any?

    fun hasLocal(name: String): Boolean

    fun setLocal(name: String, value: Any?)

    val game: GameContext

    val event: TriggerEvent

    /** Instance tick counter. */
    val tick: Long

    /** Suspend this runner until the given instance tick (the node must also return YIELD). */
    fun sleepUntil(tick: Long)

    /** Stops this runner after the current node. */
    fun stopScript()

    /** True until the runner first yields: the only time an event can still be cancelled. */
    val inSyncSection: Boolean

    fun warn(message: String)

    // ---- typed helpers ----

    fun number(e: Expr): Double = Values.toNumber(eval(e))
    fun integer(e: Expr): Int = kotlin.math.floor(number(e)).toInt()
    fun string(e: Expr): String = Values.toText(eval(e))
    fun bool(e: Expr): Boolean = Values.toBoolean(eval(e))
    fun player(e: Expr): GamePlayer? = eval(e) as? GamePlayer
    fun team(e: Expr): GameTeam? = eval(e) as? GameTeam
    fun location(e: Expr): Location? = Values.toLocation(eval(e), game.world)
    fun region(e: Expr): Region? = eval(e) as? Region
    fun material(e: Expr): Material? = Values.toMaterial(eval(e))
    fun list(e: Expr): List<Any?> = Values.toList(eval(e))
}

/** A participant. Becomes invalid when they leave; operations on an invalid player do nothing. */
interface GamePlayer {
    val uuid: UUID
    val name: String

    /** The online Bukkit player while they are in this instance, otherwise null. */
    val player: Player?

    val isValid: Boolean get() = player != null
    val isAlive: Boolean
    val isSpectator: Boolean
    val team: GameTeam?
}

interface GameTeam {
    val id: String
    val displayName: String
    val color: NamedTextColor
    val members: List<GamePlayer>
    val aliveMembers: List<GamePlayer> get() = members.filter { it.isAlive }
}

/** A region: union of boxes in the instance world. */
data class Region(val name: String, val world: World, val boxes: List<BlockBox>) {
    fun contains(loc: Location?): Boolean {
        if (loc == null || loc.world != world) return false
        return boxes.any { it.containsPoint(loc.x, loc.y, loc.z) }
    }

    fun containsBlock(x: Int, y: Int, z: Int): Boolean = boxes.any { it.contains(x, y, z) }

    fun volume(): Long = boxes.sumOf { it.volume() }

    /** Uniform random block position (weighted by box volume), centered in the block. */
    fun randomLocation(random: Random): Location {
        var pick = (random.nextDouble() * volume()).toLong()
        for (b in boxes) {
            if (pick < b.volume()) {
                val x = b.minX + random.nextInt(b.maxX - b.minX + 1)
                val y = b.minY + random.nextInt(b.maxY - b.minY + 1)
                val z = b.minZ + random.nextInt(b.maxZ - b.minZ + 1)
                return Location(world, x + 0.5, y.toDouble(), z + 0.5)
            }
            pick -= b.volume()
        }
        val b = boxes.first()
        return Location(world, b.minX + 0.5, b.minY.toDouble(), b.minZ + 0.5)
    }
}

/**
 * Context bound to a runner when its trigger fires (spec §7.3). Cancellation is only honoured
 * while the handler is still in its synchronous section.
 */
class TriggerEvent(val type: String, val cancellable: Boolean) {
    var cancelled: Boolean = false
        set(value) {
            if (cancellable) field = value
        }
    var player: GamePlayer? = null
    var otherPlayer: GamePlayer? = null
    var blockLocation: Location? = null
    var material: Material? = null
    var entity: Entity? = null

    /** Message name / variable name / region name / damage cause. */
    var key: String? = null

    /** Message value / new variable value / winner. */
    var value: Any? = null

    /** Damage amount etc. */
    var amount: Double = 0.0
    val extra: MutableMap<String, Any?> = HashMap()

    fun player(p: GamePlayer?) = apply { player = p }
    fun otherPlayer(p: GamePlayer?) = apply { otherPlayer = p }
    fun blockLocation(l: Location?) = apply { blockLocation = l }
    fun material(m: Material?) = apply { material = m }
    fun entity(e: Entity?) = apply { entity = e }
    fun key(k: String?) = apply { key = k }
    fun value(v: Any?) = apply { value = v }
    fun amount(a: Double) = apply { amount = a }

    companion object {
        @JvmField val NONE = TriggerEvent("none", false)
    }
}

/**
 * What blocks may do to their game instance. Every method must be called from the instance's own
 * thread (spec §12.8 convention 3).
 */
interface GameContext {
    val instanceId: String
    val gameId: String
    val gameVersion: Int
    val mapId: String
    val world: World
    val state: GameState
    val tick: Long

    /** Seconds until the time limit, or -1 when there is none. */
    val timeLeftSeconds: Int
    val random: Random

    // ---- participants ----

    /** Everyone in the instance (alive, eliminated and spectating). */
    fun players(): List<GamePlayer>
    fun alivePlayers(): List<GamePlayer>
    fun wrap(player: Player): GamePlayer?
    fun teams(): List<GameTeam>

    // ---- map ----

    /** All locations registered under a point name (empty if undefined). */
    fun points(name: String): List<Location>
    fun region(name: String?): Region?

    // ---- game flow ----

    /** Ends the game. [winner] is a [GamePlayer], a [GameTeam] or null (no winner). */
    fun endGame(winner: Any?)
    fun eliminate(player: GamePlayer)
    fun makeSpectator(player: GamePlayer)

    /** Stops every runner in the instance ("stop all"). */
    fun stopAll()

    /** Sends a message to `message_received` scripts. The handle reports when they are all done. */
    fun broadcast(message: String, value: Any?): BroadcastHandle

    // ---- variables ----

    fun getVariable(name: String, target: Any?): Any?
    fun setVariable(name: String, target: Any?, value: Any?)

    // ---- resources (limited per instance, spec §7.4) ----

    val limits: Limits

    /** Spawns an entity counted against the instance limit; throws [ScriptError] when over it. */
    fun spawnTracked(location: Location, type: EntityType): Entity?
    fun trackView(owner: GamePlayer, handle: ViewHandle)
    fun <T : Provider> provider(type: Class<T>): T?
    fun kit(name: String): List<ItemStack>

    // ---- display (separated per instance) ----

    fun setSidebarTitle(target: GamePlayer?, miniMessage: String)
    fun setSidebarLine(target: GamePlayer?, line: Int, miniMessage: String?)
    fun showBossBar(id: String, target: GamePlayer?, miniMessage: String, progress: Float, color: String)
    fun hideBossBar(id: String, target: GamePlayer?)

    /** Sends a message to every participant of this instance only. */
    fun announce(miniMessage: String)
    fun warn(message: String)
}
