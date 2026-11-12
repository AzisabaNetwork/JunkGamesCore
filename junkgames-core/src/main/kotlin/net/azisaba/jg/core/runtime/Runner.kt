package net.azisaba.jg.core.runtime

import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Frame
import net.azisaba.jg.api.runtime.GameContext
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.Signal
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.TriggerEvent
import net.azisaba.jg.core.compile.CompiledGame

/**
 * One execution of a script (spec §7.1): a lightweight coroutine with an explicit frame stack.
 * No thread is assigned; [step] runs it until it yields, finishes or runs out of budget.
 */
class Runner internal constructor(
    private val rt: InstanceRuntime,
    val script: CompiledGame.Script?,
    override val event: TriggerEvent,
    body: Stmt?,
    private val nested: Boolean = false,
    override var callDepth: Int = 0,
) : Exec {
    enum class Result { YIELDED, EXHAUSTED, FINISHED }

    private val stack = ArrayList<Frame>()
    private val scriptLocals = HashMap<String, Any?>()

    internal var steps = 0
        private set
    private var wakeTick = 0L
    private var stopped = false
    var isFinished = false
        private set
    override var inSyncSection = true
        private set
    internal var exhaustedStreak = 0
    private var returnValue: Any? = null
    private var currentFrame: Frame? = null
    private var currentExpr: Expr? = null

    init {
        if (body != null) stack += Frame(body, null)
    }

    // ------------------------------------------------------------------ scheduling

    fun step(budget: Int): Result {
        steps = 0
        try {
            while (true) {
                if (stopped || stack.isEmpty()) {
                    finish()
                    return Result.FINISHED
                }
                if (steps >= budget) return Result.EXHAUSTED
                val f = stack.last()
                currentFrame = f
                currentExpr = null
                steps++
                val s = f.node.run(this, f)
                if (stopped) {
                    finish()
                    return Result.FINISHED
                }
                when (s) {
                    // a "return" may already have unwound past this frame
                    Signal.DONE -> if (stack.isNotEmpty() && stack.last() === f) pop()
                    Signal.PUSHED -> {}
                    Signal.YIELD -> {
                        inSyncSection = false
                        if (wakeTick <= rt.tick) wakeTick = rt.tick + 1
                        return Result.YIELDED
                    }
                }
            }
        } catch (e: NestedFailure) {
            throw e
        } catch (e: ScriptError) {
            fail(e.message ?: "error")
        } catch (e: StackOverflowError) {
            fail("処理が深すぎます")
        } catch (e: RuntimeException) {
            if (!nested) rt.reportInternal(this, e)
            fail("内部エラー: $e")
        }
        return Result.FINISHED
    }

    private fun fail(message: String) {
        val nodeId = errorNodeId()
        finish()
        if (nested) throw NestedFailure(message)
        rt.reportError(this, nodeId, message)
    }

    private fun finish() {
        isFinished = true
        stack.clear()
    }

    private fun pop() {
        val f = stack.removeLast()
        if (f.isCallFrame) callDepth--
    }

    internal fun errorNodeId(): String? {
        val g = rt.game
        var id = currentExpr?.let { g.nodeId(it) } ?: currentFrame?.let { g.nodeId(it.node) }
        var i = stack.size - 1
        while (id == null && i >= 0) id = g.nodeId(stack[i--].node)
        return id
    }

    /** Node id currently executing (live debugging highlight, spec E-6). */
    fun currentNodeId(): String? = currentFrame?.let { rt.game.nodeId(it.node) }

    internal fun isSleeping(tick: Long) = wakeTick > tick

    internal fun endSyncSection() {
        inSyncSection = false
    }

    /** Stops from outside (restart / stop all / instance end). Safe while this runner is executing. */
    internal fun stopExternally() {
        stopped = true
        finish()
    }

    /** Synchronous call of a custom reporter: nested runner with a hard step limit; waiting is an error. */
    internal fun callSync(body: Stmt, locals: MutableMap<String, Any?>): Any? {
        val sub = Runner(rt, script, event, null, nested = true, callDepth = callDepth)
        sub.inSyncSection = inSyncSection
        sub.pushCall(body, locals)
        val r = try {
            sub.step(rt.limits.reporterStepLimit)
        } catch (nf: NestedFailure) {
            throw ScriptError(nf.message ?: "error")
        } finally {
            steps += sub.steps
        }
        return when (r) {
            Result.YIELDED -> throw ScriptError("値を返す自作ブロックの中では待機できません")
            Result.EXHAUSTED -> throw ScriptError("値を返す自作ブロックの処理が多すぎます（上限 ${rt.limits.reporterStepLimit} ステップ）")
            Result.FINISHED -> sub.returnValue
        }
    }

    private class NestedFailure(message: String) : ScriptError(message)

    // ------------------------------------------------------------------ Exec

    override fun eval(expr: Expr): Any? {
        steps++
        val prev = currentExpr
        currentExpr = expr
        val v = expr.eval(this)
        currentExpr = prev
        return v
    }

    override fun push(stmt: Stmt) {
        stack += Frame(stmt, null)
    }

    override fun pushCall(body: Stmt, locals: MutableMap<String, Any?>) {
        if (callDepth + 1 > rt.limits.maxCallDepth) {
            throw ScriptError("自作ブロックの呼び出しが深すぎます（上限 ${rt.limits.maxCallDepth}）")
        }
        callDepth++
        stack += Frame(body, locals)
    }

    override fun returnFromCall(value: Any?) {
        returnValue = value
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isCallFrame) {
                callDepth--
                return
            }
        }
        stopped = true // "return" outside of a custom block ends the script
    }

    private fun localScope(): MutableMap<String, Any?> {
        for (i in stack.indices.reversed()) stack[i].locals?.let { return it }
        return scriptLocals
    }

    override fun getLocal(name: String): Any? = localScope()[name]
    override fun hasLocal(name: String): Boolean = name in localScope()
    override fun setLocal(name: String, value: Any?) {
        localScope()[name] = value
    }

    override val game: GameContext get() = rt.context
    override val tick: Long get() = rt.tick
    override fun sleepUntil(tick: Long) {
        wakeTick = tick
    }

    override fun stopScript() {
        stopped = true
    }

    override fun warn(message: String) = rt.reportWarning(this, errorNodeId(), message)
}
