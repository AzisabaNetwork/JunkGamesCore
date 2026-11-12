package net.azisaba.jg.core.runtime

import net.azisaba.jg.api.ir.IrScript
import net.azisaba.jg.api.runtime.BroadcastHandle
import net.azisaba.jg.api.runtime.GameContext
import net.azisaba.jg.api.runtime.GameState
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.api.runtime.TriggerSpec
import net.azisaba.jg.core.compile.CompiledGame
import java.util.ArrayDeque
import java.util.UUID

/** Receives runtime diagnostics of one instance. Must not do I/O on the calling thread. */
interface RuntimeListener {
    fun runtimeError(scriptId: String, nodeId: String?, message: String)
    fun runtimeWarning(scriptId: String, nodeId: String?, message: String)

    /** Unexpected exception inside a block implementation (a bug, not a script error). */
    fun internalError(scriptId: String, t: Throwable) {}

    companion object {
        val NONE = object : RuntimeListener {
            override fun runtimeError(scriptId: String, nodeId: String?, message: String) {}
            override fun runtimeWarning(scriptId: String, nodeId: String?, message: String) {}
        }
    }
}

/**
 * Script scheduler of one game instance (spec §7.2). Not thread-safe by design: owned by the
 * instance and only touched from the thread that ticks the instance's world.
 *
 * Re-entrancy: Bukkit events may fire while a block is executing (e.g. setting health to 0 fires
 * a death event), so [fireSync] can run inside [runTick]. Runners are therefore only *marked*
 * finished during execution; the list is compacted when the outermost call returns.
 */
class InstanceRuntime(
    val game: CompiledGame,
    val context: GameContext,
    val limits: Limits,
    private val listener: RuntimeListener,
) {
    private val runners = ArrayList<Runner>()
    private val pending = ArrayDeque<TriggerEvent>()
    private val byScript = HashMap<Int, MutableList<Runner>>()
    private val perPlayer = HashMap<Int, MutableMap<UUID, Runner>>()
    private var cursor = 0
    private var depth = 0

    var tick = 0L
        private set

    // metrics (spec §16)
    var stepsLastTick = 0
        private set
    var totalSteps = 0L
        private set
    var errorCount = 0
        private set
    val runnerCount: Int get() = runners.count { !it.isFinished }

    // ------------------------------------------------------------------ events

    /** Queues an event; its runners start at the beginning of the next [runTick]. */
    fun post(event: TriggerEvent) {
        pending.add(event)
    }

    /**
     * Fires an event now and runs the new runners synchronously until they first wait, so that they
     * can still cancel the event (spec §7.3). Check [TriggerEvent.cancelled] afterwards.
     */
    fun fireSync(event: TriggerEvent): TriggerEvent {
        if (depth >= MAX_SYNC_DEPTH) {
            // event -> script -> event -> ... : stop recursing, deliver next tick (no cancel)
            post(event)
            return event
        }
        depth++
        try {
            for (r in start(event)) {
                if (r.isFinished) continue
                val res = r.step(limits.perRunnerBudget)
                stepsLastTick += r.steps
                totalSteps += r.steps
                r.endSyncSection()
                if (res == Runner.Result.EXHAUSTED) r.exhaustedStreak++
            }
        } finally {
            depth--
        }
        if (depth == 0) cleanup()
        return event
    }

    /** Starts runners for every matching script (not yet stepped). */
    private fun start(event: TriggerEvent): List<Runner> {
        val scripts = game.scriptsFor(event.type)
        if (scripts.isEmpty()) return emptyList()
        val state = context.state
        val started = ArrayList<Runner>(2)
        for (s in scripts) {
            val t = s.trigger
            if (t.activeIn == TriggerSpec.ActiveIn.RUNNING && state != GameState.RUNNING) continue
            if (t.key != null && t.key != event.key) continue
            val match = try {
                t.filter.test(context, event)
            } catch (e: RuntimeException) {
                listener.runtimeError(s.id, game.nodeId(t), "トリガーの判定でエラー: ${e.message}")
                false
            }
            if (!match) continue
            if (t.autoCancel) event.cancelled = true
            startScript(s, event)?.let { started += it }
        }
        return started
    }

    private fun startScript(s: CompiledGame.Script, event: TriggerEvent): Runner? {
        val active = byScript.getOrPut(s.index) { ArrayList() }
        active.removeIf { it.isFinished }
        when (s.concurrency) {
            IrScript.Concurrency.DROP -> if (active.isNotEmpty()) return null
            IrScript.Concurrency.RESTART -> {
                active.forEach { it.stopExternally() }
                active.clear()
            }
            IrScript.Concurrency.PER_PLAYER -> {
                val p = event.player
                if (p != null && perPlayer[s.index]?.get(p.uuid)?.isFinished == false) return null
            }
            IrScript.Concurrency.PARALLEL -> {}
        }
        if (runnerCount >= limits.maxRunners) {
            listener.runtimeWarning(s.id, game.nodeId(s.trigger), "同時に実行できるランナー数の上限（${limits.maxRunners}）に達したため、起動しませんでした")
            return null
        }
        val r = Runner(this, s, event, s.body)
        runners += r
        active += r
        val p = event.player
        if (s.concurrency == IrScript.Concurrency.PER_PLAYER && p != null) {
            perPlayer.getOrPut(s.index) { HashMap() }[p.uuid] = r
        }
        return r
    }

    /** Broadcast: starts receivers now (they run later this tick). */
    fun broadcast(message: String, value: Any?): BroadcastHandle {
        val started = start(TriggerEvent("message", false).key(message).value(value))
        return BroadcastHandle { started.all { it.isFinished } }
    }

    // ------------------------------------------------------------------ tick

    /**
     * One tick: deliver queued events, fire timers, then step ready runners with per-runner and
     * per-instance budgets. Runaway runners are stopped (spec §7.2).
     */
    fun runTick() {
        depth++
        try {
            tickBody()
        } finally {
            depth--
        }
        if (depth == 0) cleanup()
    }

    private fun tickBody() {
        tick++
        stepsLastTick = 0
        var delivered = 0
        while (delivered++ < 10_000) start(pending.poll() ?: break)
        fireTimers()

        var budgetLeft = limits.perInstanceBudget
        val size = runners.size
        if (size == 0) return
        val first = cursor % size
        var outOfBudget = false
        // existing runners in rotated order (fairness when the instance budget runs out),
        // then runners appended during this loop (broadcast receivers, events)
        var k = 0
        while (k < runners.size) {
            val idx = if (k < size) (first + k) % size else k
            k++
            val r = runners[idx]
            if (r.isFinished || r.isSleeping(tick)) continue
            if (budgetLeft <= 0) {
                cursor = if (idx < size) idx else 0 // the rest continues next tick
                outOfBudget = true
                break
            }
            val budget = minOf(limits.perRunnerBudget, budgetLeft)
            val res = r.step(budget)
            budgetLeft -= r.steps
            stepsLastTick += r.steps
            totalSteps += r.steps
            if (res == Runner.Result.EXHAUSTED && budget == limits.perRunnerBudget) {
                if (++r.exhaustedStreak >= limits.runawayTicks) {
                    val id = r.errorNodeId()
                    r.stopExternally()
                    reportError(
                        r, id,
                        "暴走を検出したため停止しました（${limits.runawayTicks} tick 連続で実行予算を使い切りました。ループに待機を入れてください）",
                    )
                }
            } else if (res != Runner.Result.EXHAUSTED) {
                r.exhaustedStreak = 0
            }
        }
        if (!outOfBudget) cursor = 0
    }

    private fun fireTimers() {
        if (context.state != GameState.RUNNING) return
        for (s in game.scriptsFor("timer")) {
            if (tick % maxOf(1, s.trigger.intervalTicks) == 0L) startScript(s, TriggerEvent("timer", false))
        }
    }

    private fun cleanup() {
        runners.removeIf { it.isFinished }
        if (cursor > runners.size) cursor = 0
    }

    /** Stops every runner. Safe to call from inside a running block. */
    fun stopAll() {
        for (i in runners.indices) runners[i].stopExternally()
        byScript.clear()
        perPlayer.clear()
        pending.clear()
        if (depth == 0) cleanup()
    }

    /** Stops runners whose context player is [uuid] (player left). */
    fun stopPlayerRunners(uuid: UUID) {
        for (i in runners.indices) {
            val r = runners[i]
            if (r.event.player?.uuid == uuid) r.stopExternally()
        }
        if (depth == 0) cleanup()
    }

    /** Node ids currently executing, for live debugging. */
    fun activeNodeIds(): List<String> = runners.filter { !it.isFinished }.mapNotNull { it.currentNodeId() }

    // ------------------------------------------------------------------ reporting

    internal fun reportError(r: Runner, nodeId: String?, message: String) {
        errorCount++
        listener.runtimeError(r.script?.id ?: "?", nodeId, message)
    }

    internal fun reportWarning(r: Runner, nodeId: String?, message: String) =
        listener.runtimeWarning(r.script?.id ?: "?", nodeId, message)

    internal fun reportInternal(r: Runner, t: Throwable) = listener.internalError(r.script?.id ?: "?", t)

    private companion object {
        const val MAX_SYNC_DEPTH = 8
    }
}
