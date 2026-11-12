package net.azisaba.jg.api.runtime

import net.azisaba.jg.api.type.ValueType

/** Compiled, executable form of an IR node: an [Expr], a [Stmt] or a [TriggerSpec]. */
interface Node

/**
 * Reporter / boolean block. Evaluated synchronously. Never call [eval] directly from another
 * node — use [Exec.eval] so that steps are counted.
 */
interface Expr : Node {
    fun eval(ex: Exec): Any?

    val type: ValueType

    /** A literal / constant value (trigger blocks read these at compile time). */
    class Constant(override val type: ValueType, val value: Any?) : Expr {
        override fun eval(ex: Exec): Any? = value
    }

    companion object {
        @JvmStatic
        fun of(type: ValueType, fn: (Exec) -> Any?): Expr = object : Expr {
            override fun eval(ex: Exec): Any? = fn(ex)
            override val type: ValueType get() = type
        }

        @JvmStatic
        fun constant(type: ValueType, value: Any?): Expr = Constant(type, value)
    }
}

enum class Signal { DONE, PUSHED, YIELD }

/**
 * Statement block, executed by the coroutine VM (spec §7.1).
 *
 * The runner keeps a frame stack. While a frame is on top, [run] is called repeatedly; each call
 * costs one step. Return:
 * - [Signal.DONE] – the frame is popped
 * - [Signal.PUSHED] – call me again (after a child pushed with [Exec.push] finishes, or right away)
 * - [Signal.YIELD] – suspend the runner until the next tick (or [Exec.sleepUntil])
 *
 * Simple blocks should implement [SimpleStmt].
 */
fun interface Stmt : Node {
    fun run(ex: Exec, frame: Frame): Signal
}

/** A statement that completes immediately. */
fun interface SimpleStmt : Stmt {
    fun execute(ex: Exec)

    override fun run(ex: Exec, frame: Frame): Signal {
        execute(ex)
        return Signal.DONE
    }
}

/**
 * One entry of a runner's frame stack: "which node, and how far into it". Statements keep
 * their per-execution state here — never in fields of the (shared) Stmt.
 */
class Frame(
    @JvmField val node: Stmt,
    /** Non-null on custom-block call frames: the callee's local variables. */
    @JvmField val locals: MutableMap<String, Any?>?,
) {
    /** Program counter / phase. */
    @JvmField var pc: Int = 0

    /** Numeric scratch (loop counter, ...). */
    @JvmField var n: Long = 0

    /** Arbitrary per-execution state (iteration snapshot, broadcast handle, ...). */
    @JvmField var state: Any? = null

    val isCallFrame: Boolean get() = locals != null
}

/**
 * Runtime error. Stops only the current runner; the instance keeps going (spec §7.5).
 * Operations on invalid values (players who left, ...) must NOT throw this — they are no-ops.
 */
open class ScriptError(message: String) : RuntimeException(message, null, false, false)

/** Lifecycle managed by the core (spec §4). Blocks can only request [GameContext.endGame]. */
enum class GameState { IDLE, WAITING, COUNTDOWN, RUNNING, ENDING, RESET }

/** Per-instance resource limits (spec §7.4) and budgets (spec §7.2). */
data class Limits(
    val perRunnerBudget: Int = 1_000,
    val perInstanceBudget: Int = 20_000,
    val runawayTicks: Int = 200,
    val maxRunners: Int = 512,
    val maxEntities: Int = 200,
    val maxFillBlocksPerTick: Int = 32_768,
    val maxListSize: Int = 10_000,
    val maxStringLength: Int = 4_096,
    val maxCallDepth: Int = 64,
    val reporterStepLimit: Int = 10_000,
    val errorThresholdPerMinute: Int = 10,
)

/** Returned by [GameContext.broadcast]; used by "broadcast and wait". */
fun interface BroadcastHandle {
    fun isDone(): Boolean
}

/**
 * Compiled hat block. The core indexes scripts by [eventType] and checks [filter] when an event
 * of that type happens in the instance.
 *
 * @property key event-specific key: region name, message name, variable name (or null)
 * @property intervalTicks for timer triggers
 * @property autoCancel cancel the underlying Bukkit event whenever this trigger matches
 */
data class TriggerSpec(
    val eventType: String,
    val key: String? = null,
    val intervalTicks: Int = 0,
    val filter: Filter = Filter.ALWAYS,
    val autoCancel: Boolean = false,
    val activeIn: ActiveIn = ActiveIn.RUNNING,
) : Node {
    enum class ActiveIn {
        /** Only while RUNNING (default for gameplay events). */
        RUNNING,

        /** In every state where the instance exists. */
        ALWAYS,
    }

    fun interface Filter {
        fun test(game: GameContext, event: TriggerEvent): Boolean

        companion object {
            @JvmField val ALWAYS = Filter { _, _ -> true }
        }
    }

    companion object {
        @JvmStatic
        fun of(eventType: String) = TriggerSpec(eventType)
    }
}
