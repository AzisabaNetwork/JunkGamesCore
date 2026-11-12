package net.azisaba.jg.core.runtime

import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Frame
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.Signal
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.compile.CompiledGame

/** Nodes for calling user-defined blocks (spec §5.6). Recursion is bounded by `maxCallDepth`. */
object CustomCalls {
    fun statement(target: CompiledGame.CustomBlock, args: Map<String, Expr>): Stmt = CallStmt(target, args)

    fun reporter(target: CompiledGame.CustomBlock, args: Map<String, Expr>): Expr = CallExpr(target, args)

    private fun evalArgs(ex: Exec, args: Map<String, Expr>): MutableMap<String, Any?> =
        args.mapValuesTo(HashMap()) { (_, e) -> Values.copyIfList(ex.eval(e)) }

    private class CallStmt(val target: CompiledGame.CustomBlock, val args: Map<String, Expr>) : Stmt {
        override fun run(ex: Exec, frame: Frame): Signal {
            if (frame.pc == 0) {
                frame.pc = 1
                ex.pushCall(target.body, evalArgs(ex, args))
                return Signal.PUSHED
            }
            return Signal.DONE
        }
    }

    /** Runs the callee synchronously in a nested runner; it may not wait. */
    private class CallExpr(val target: CompiledGame.CustomBlock, val args: Map<String, Expr>) : Expr {
        override fun eval(ex: Exec): Any? {
            val r = ex as? Runner ?: throw ScriptError("自作ブロックをこの文脈では呼べません")
            return r.callSync(target.body, evalArgs(ex, args))
        }

        override val type: ValueType get() = target.ir.returns ?: ValueType.NONE
    }
}
