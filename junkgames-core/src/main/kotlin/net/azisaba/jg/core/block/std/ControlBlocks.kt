package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.CompileContext
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.runtime.BroadcastHandle
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Signal
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import kotlin.math.roundToLong

/** `control.*` (spec §6.2). Loops honour `meta.loopYield`. */
internal object ControlBlocks {
    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.stack("control.wait_seconds").category(Category.CONTROL)
            .label("%seconds 秒待つ", "wait %seconds seconds")
            .input("seconds", ValueType.NUMBER, 1)
            .compiler { ctx, ir -> wait(ctx, ctx.input(ir, "seconds")!!, 20) },

        BlockBuilder.stack("control.wait_ticks").category(Category.CONTROL)
            .label("%ticks ティック待つ", "wait %ticks ticks")
            .input("ticks", ValueType.NUMBER, 1)
            .compiler { ctx, ir -> wait(ctx, ctx.input(ir, "ticks")!!, 1) },

        BlockBuilder.stack("control.wait_until").category(Category.CONTROL)
            .label("%condition まで待つ", "wait until %condition")
            .input("condition", ValueType.BOOLEAN, false)
            .compiler { ctx, ir ->
                noWaitInReporter(ctx)
                val cond = ctx.input(ir, "condition")!!
                Stmt { ex, _ -> if (ex.bool(cond)) Signal.DONE else Signal.YIELD }
            },

        BlockBuilder.cBlock("control.if").category(Category.CONTROL)
            .label("もし %condition なら %do", "if %condition then %do")
            .input("condition", ValueType.BOOLEAN, false).body("do")
            .compiler { ctx, ir ->
                val cond = ctx.input(ir, "condition")!!
                val body = ctx.body(ir, "do")
                Stmt { ex, f ->
                    if (f.pc == 0) {
                        f.pc = 1
                        if (ex.bool(cond)) {
                            ex.push(body)
                            return@Stmt Signal.PUSHED
                        }
                    }
                    Signal.DONE
                }
            },

        BlockBuilder.cBlock("control.if_else").category(Category.CONTROL)
            .label("もし %condition なら %do でなければ %else", "if %condition then %do else %else")
            .input("condition", ValueType.BOOLEAN, false).body("do").body("else")
            .compiler { ctx, ir ->
                val cond = ctx.input(ir, "condition")!!
                val then = ctx.body(ir, "do")
                val otherwise = ctx.body(ir, "else")
                Stmt { ex, f ->
                    if (f.pc == 0) {
                        f.pc = 1
                        ex.push(if (ex.bool(cond)) then else otherwise)
                        Signal.PUSHED
                    } else Signal.DONE
                }
            },

        BlockBuilder.cBlock("control.repeat").category(Category.CONTROL)
            .label("%times 回繰り返す %do", "repeat %times %do")
            .input("times", ValueType.NUMBER, 10).body("do")
            .compiler { ctx, ir ->
                val times = ctx.input(ir, "times")!!
                val body = ctx.body(ir, "do")
                val yieldEach = loopYield(ctx)
                Stmt { ex, f ->
                    when (f.pc) {
                        0 -> {
                            f.n = maxOf(0L, ex.number(times).roundToLong())
                            f.pc = 1
                            Signal.PUSHED // PUSHED without pushing = "run me again"
                        }
                        1 -> if (f.n <= 0) Signal.DONE else {
                            f.n--
                            f.pc = 2
                            ex.push(body)
                            Signal.PUSHED
                        }
                        else -> {
                            f.pc = 1
                            if (yieldEach) Signal.YIELD else Signal.PUSHED
                        }
                    }
                }
            },

        BlockBuilder.cBlock("control.forever").category(Category.CONTROL)
            .label("ずっと %do", "forever %do")
            .body("do")
            .compiler { ctx, ir ->
                val body = ctx.body(ir, "do")
                val yieldEach = loopYield(ctx)
                Stmt { ex, f ->
                    if (f.pc == 0) {
                        f.pc = 1
                        ex.push(body)
                        Signal.PUSHED
                    } else {
                        f.pc = 0
                        if (yieldEach) Signal.YIELD else Signal.PUSHED
                    }
                }
            },

        BlockBuilder.cBlock("control.repeat_until").category(Category.CONTROL)
            .label("%condition まで繰り返す %do", "repeat until %condition %do")
            .input("condition", ValueType.BOOLEAN, false).body("do")
            .compiler { ctx, ir ->
                val cond = ctx.input(ir, "condition")!!
                val body = ctx.body(ir, "do")
                val yieldEach = loopYield(ctx)
                Stmt { ex, f ->
                    if (f.pc == 0) {
                        if (ex.bool(cond)) return@Stmt Signal.DONE
                        f.pc = 1
                        ex.push(body)
                        Signal.PUSHED
                    } else {
                        f.pc = 0
                        if (yieldEach) Signal.YIELD else Signal.PUSHED
                    }
                }
            },

        forEach(
            "control.for_each_player", "%which の各プレイヤー %var について %do", ValueType.PLAYER,
            listOf("alive", "all", "spectators"),
        ) { which ->
            { ex ->
                when (which) {
                    "all" -> ex.game.players()
                    "spectators" -> ex.game.players().filter { it.isSpectator }
                    else -> ex.game.alivePlayers()
                }
            }
        },

        forEach("control.for_each_team", "各チーム %var について %do", ValueType.TEAM, emptyList()) {
            { ex -> ex.game.teams() }
        },

        BlockBuilder.cBlock("control.for_each_item").category(Category.CONTROL)
            .label("リスト %list の各要素 %var について %do", "for each %var in %list %do")
            .field(FieldSpec.text("var", "item")).input("list", ValueType.listOf(ValueType.ANY)).body("do")
            .compiler { ctx, ir ->
                val v = ctx.field(ir, "var")
                val list = ctx.input(ir, "list")!!
                val elem = (list.type as? ValueType.ListOf)?.element ?: ValueType.ANY
                ctx.pushLocal(v, elem)
                val body = try {
                    ctx.body(ir, "do")
                } finally {
                    ctx.popLocal()
                }
                iterate(v, { ex -> ex.list(list) }, body, loopYield(ctx))
            },

        BlockBuilder.cap("control.stop_script").category(Category.CONTROL)
            .label("このスクリプトを止める", "stop this script")
            .exec { ex, _ -> ex.stopScript() },

        BlockBuilder.cap("control.stop_all").category(Category.CONTROL)
            .label("すべて止める", "stop all")
            .exec { ex, _ ->
                ex.stopScript()
                ex.game.stopAll()
            },

        BlockBuilder.stack("control.broadcast").category(Category.CONTROL)
            .label("メッセージ %message を送る（値: %value）", "broadcast %message with %value")
            .field(FieldSpec.of("message", FieldSpec.Kind.MESSAGE)).optionalInput("value", ValueType.ANY)
            .exec { ex, a -> ex.game.broadcast(a.field("message"), Values.copyIfList(a.get("value"))) },

        BlockBuilder.stack("control.broadcast_and_wait").category(Category.CONTROL)
            .label("メッセージ %message を送って待つ（値: %value）", "broadcast %message with %value and wait")
            .field(FieldSpec.of("message", FieldSpec.Kind.MESSAGE)).optionalInput("value", ValueType.ANY)
            .compiler { ctx, ir ->
                noWaitInReporter(ctx)
                val msg = ctx.field(ir, "message")
                val value = ctx.input(ir, "value")
                Stmt { ex, f ->
                    if (f.pc == 0) {
                        f.pc = 1
                        f.state = ex.game.broadcast(msg, value?.let { Values.copyIfList(ex.eval(it)) })
                    }
                    if ((f.state as BroadcastHandle).isDone()) Signal.DONE else Signal.YIELD
                }
            },

        BlockBuilder.cap("control.return").category(Category.CUSTOM)
            .label("%value を返す", "return %value")
            .optionalInput("value", ValueType.ANY)
            .exec { ex, a -> ex.returnFromCall(Values.copyIfList(a.get("value"))) },
    )

    private fun wait(ctx: CompileContext, amount: Expr, ticksPerUnit: Int): Stmt {
        noWaitInReporter(ctx)
        return Stmt { ex, f ->
            if (f.pc == 0) {
                f.pc = 1
                val ticks = maxOf(0L, (ex.number(amount) * ticksPerUnit).roundToLong())
                ex.sleepUntil(ex.tick + maxOf(1L, ticks)) // "wait 0" still yields once (Scratch)
                Signal.YIELD
            } else Signal.DONE
        }
    }

    private fun noWaitInReporter(ctx: CompileContext) {
        if (ctx.inReporterBody) throw CompileException("値を返す自作ブロックの中では待機できません")
    }

    private fun loopYield(ctx: CompileContext) = ctx.meta.loopYield && !ctx.inReporterBody

    private fun forEach(
        id: String, label: String, elem: ValueType, options: List<String>,
        source: (String) -> (Exec) -> List<Any?>,
    ): BlockDefinition {
        val b = BlockBuilder.cBlock(id).category(Category.CONTROL).label(label)
            .field(FieldSpec.text("var", if (elem == ValueType.PLAYER) "p" else "t")).body("do")
        if (options.isNotEmpty()) b.field(FieldSpec(name = "which", kind = FieldSpec.Kind.DROPDOWN, options = options, defaultValue = options.first()))
        return b.compiler { ctx, ir ->
            val v = ctx.field(ir, "var")
            val which = if (options.isEmpty()) "" else ctx.field(ir, "which")
            ctx.pushLocal(v, elem)
            val body = try {
                ctx.body(ir, "do")
            } finally {
                ctx.popLocal()
            }
            iterate(v, source(which), body, loopYield(ctx))
        }
    }

    /** Iterates over a snapshot taken when the loop starts. */
    private fun iterate(v: String, source: (Exec) -> List<Any?>, body: Stmt, yieldEach: Boolean) = Stmt { ex, f ->
        when (f.pc) {
            0 -> {
                f.state = ArrayList(source(ex))
                f.n = 0
                f.pc = 1
                Signal.PUSHED
            }
            1 -> {
                @Suppress("UNCHECKED_CAST")
                val items = f.state as List<Any?>
                if (f.n >= items.size) Signal.DONE else {
                    ex.setLocal(v, items[(f.n++).toInt()])
                    f.pc = 2
                    ex.push(body)
                    Signal.PUSHED
                }
            }
            else -> {
                f.pc = 1
                if (yieldEach) Signal.YIELD else Signal.PUSHED
            }
        }
    }
}
