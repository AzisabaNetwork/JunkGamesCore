package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.CompileContext
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.SimpleStmt
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.compile.GameCompiler

/**
 * Resolves a variable reference: a local (loop variable / custom block parameter) first, then a
 * declared variable. Player / team scoped variables take an optional `target` input
 * (default: this player / this player's team).
 */
internal class VarAccess(
    val name: String,
    val type: ValueType,
    private val getter: (Exec) -> Any?,
    private val setter: (Exec, Any?) -> Unit,
) {
    fun get(ex: Exec) = getter(ex)
    fun set(ex: Exec, value: Any?) = setter(ex, value)

    fun asList(): VarAccess = when {
        type is ValueType.ListOf -> this
        type.kind == ValueType.Kind.ANY -> VarAccess(name, ValueType.listOf(ValueType.ANY), getter, setter)
        else -> throw CompileException("変数 '$name' はリスト型ではありません")
    }

    companion object {
        fun compile(ctx: CompileContext, ir: IrNode): VarAccess {
            val name = ctx.field(ir, "name")
            ctx.localType(name)?.let { t ->
                return VarAccess(name, t, { it.getLocal(name) }, { ex, v -> ex.setLocal(name, v) })
            }
            val v = ctx.variable(name) ?: throw CompileException("変数 '$name' は宣言されていません")
            val target: Expr? = when (v.scope) {
                IrVariable.Scope.INSTANCE -> {
                    if ("target" in ir.inputs) ctx.warning(ir, "インスタンス変数に対象は指定できません（無視します）")
                    null
                }
                IrVariable.Scope.PLAYER -> ctx.input(ir, "target", ValueType.PLAYER)
                    ?: (ctx as GameCompiler).compileExpr(IrNode.synthetic("event.player"), ValueType.PLAYER)
                IrVariable.Scope.TEAM -> ctx.input(ir, "target", ValueType.TEAM)
                    ?: Expr.of(ValueType.TEAM) { ex -> ex.event.player?.team }
            }
            return VarAccess(
                name, v.type,
                { ex ->
                    val t = target?.let { ex.eval(it) }
                    if (target != null && t == null) null else ex.game.getVariable(name, t) // invalid target: no-op
                },
                { ex, value ->
                    val t = target?.let { ex.eval(it) }
                    if (target == null || t != null) ex.game.setVariable(name, t, value)
                },
            )
        }
    }
}

/** `var.*` and `list.*`. Lists are values: setting a variable copies the list. */
internal object VarBlocks {
    fun all(maxListSize: Int): List<BlockDefinition> = listOf(
        BlockBuilder.reporter("var.get", ValueType.ANY).category(Category.VARIABLE)
            .label("%name （%target）")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir)
                Expr.of(acc.type) { acc.get(it) }
            },

        BlockBuilder.stack("var.set").category(Category.VARIABLE)
            .label("%name （%target）を %value にする", "set %name of %target to %value")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .input("value", ValueType.ANY, 0)
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir)
                val value = ctx.input(ir, "value", acc.type)!!
                SimpleStmt { ex -> acc.set(ex, coerce(acc.type, Values.copyIfList(ex.eval(value)))) }
            },

        BlockBuilder.stack("var.change").category(Category.VARIABLE)
            .label("%name （%target）を %delta ずつ変える", "change %name of %target by %delta")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .input("delta", ValueType.NUMBER, 1)
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir)
                if (!ValueType.NUMBER.accepts(acc.type)) throw CompileException("変数 '${acc.name}' は数値型ではありません")
                val delta = ctx.input(ir, "delta")!!
                SimpleStmt { ex -> acc.set(ex, Values.toNumber(acc.get(ex)) + ex.number(delta)) }
            },

        // ---- lists (mutations go through a variable, like Scratch) ----

        BlockBuilder.stack("list.add").category(Category.LIST)
            .label("%item を %name （%target）に追加する", "add %item to %name of %target")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .input("item", ValueType.ANY, "")
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir).asList()
                val item = ctx.input(ir, "item", (acc.type as ValueType.ListOf).element)!!
                SimpleStmt { ex ->
                    val list = ArrayList(Values.toList(acc.get(ex)))
                    if (list.size >= maxListSize) throw ScriptError("リストの要素数が上限（$maxListSize）に達しました")
                    list += Values.copyIfList(ex.eval(item))
                    acc.set(ex, list)
                }
            },

        BlockBuilder.stack("list.remove_at").category(Category.LIST)
            .label("%name （%target）の %index 番目を削除する", "delete %index of %name of %target")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .input("index", ValueType.NUMBER, 1)
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir).asList()
                val index = ctx.input(ir, "index")!!
                SimpleStmt { ex ->
                    val list = ArrayList(Values.toList(acc.get(ex)))
                    val i = ex.integer(index)
                    if (i in 1..list.size) {
                        list.removeAt(i - 1)
                        acc.set(ex, list)
                    }
                }
            },

        BlockBuilder.stack("list.remove_item").category(Category.LIST)
            .label("%item を %name （%target）から削除する", "remove %item from %name of %target")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .input("item", ValueType.ANY, "")
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir).asList()
                val item = ctx.input(ir, "item")!!
                SimpleStmt { ex ->
                    val v = ex.eval(item)
                    val list = ArrayList(Values.toList(acc.get(ex)))
                    if (list.removeIf { Values.equal(it, v) }) acc.set(ex, list)
                }
            },

        BlockBuilder.stack("list.clear").category(Category.LIST)
            .label("%name （%target）を空にする", "delete all of %name of %target")
            .field(FieldSpec.of("name", FieldSpec.Kind.VARIABLE)).optionalInput("target", ValueType.ANY)
            .compiler { ctx, ir ->
                val acc = VarAccess.compile(ctx, ir).asList()
                SimpleStmt { ex -> acc.set(ex, ArrayList<Any?>()) }
            },

        BlockBuilder.reporter("list.empty", ValueType.listOf(ValueType.ANY)).category(Category.LIST)
            .label("空のリスト", "empty list")
            .eval { _, _ -> ArrayList<Any?>() },

        BlockBuilder.reporter("list.item", ValueType.ANY).category(Category.LIST)
            .label("%list の %index 番目", "item %index of %list")
            .input("list", ValueType.listOf(ValueType.ANY)).input("index", ValueType.NUMBER, 1)
            .compiler { ctx, ir ->
                val list = ctx.input(ir, "list")!!
                val index = ctx.input(ir, "index")!!
                Expr.of(elementType(list)) { ex ->
                    val l = ex.list(list)
                    val i = ex.integer(index)
                    if (i in 1..l.size) l[i - 1] else null
                }
            },

        BlockBuilder.reporter("list.random_item", ValueType.ANY).category(Category.LIST)
            .label("%list からランダムに1つ", "random item of %list")
            .input("list", ValueType.listOf(ValueType.ANY))
            .compiler { ctx, ir ->
                val list = ctx.input(ir, "list")!!
                Expr.of(elementType(list)) { ex ->
                    val l = ex.list(list)
                    if (l.isEmpty()) null else l[ex.game.random.nextInt(l.size)]
                }
            },

        BlockBuilder.reporter("list.length", ValueType.NUMBER).category(Category.LIST)
            .label("%list の長さ", "length of %list")
            .input("list", ValueType.listOf(ValueType.ANY))
            .eval { _, a -> a.list("list").size.toDouble() },

        BlockBuilder.reporter("list.index_of", ValueType.NUMBER).category(Category.LIST)
            .label("%list 中の %item の位置", "index of %item in %list")
            .input("list", ValueType.listOf(ValueType.ANY)).input("item", ValueType.ANY, "")
            .eval { _, a ->
                val v = a.get("item")
                (a.list("list").indexOfFirst { Values.equal(it, v) } + 1).toDouble()
            },

        BlockBuilder.bool("list.contains").category(Category.LIST)
            .label("%list に %item が含まれる", "%list contains %item")
            .input("list", ValueType.listOf(ValueType.ANY)).input("item", ValueType.ANY, "")
            .eval { _, a ->
                val v = a.get("item")
                a.list("list").any { Values.equal(it, v) }
            },
    )

    private fun elementType(list: Expr) = (list.type as? ValueType.ListOf)?.element ?: ValueType.ANY

    /** Numbers are stored as Double, booleans as Boolean, so equality and display are stable. */
    fun coerce(type: ValueType, value: Any?): Any? = when (type.kind) {
        ValueType.Kind.NUMBER -> Values.toNumber(value)
        ValueType.Kind.BOOLEAN -> Values.toBoolean(value)
        ValueType.Kind.STRING -> Values.toText(value)
        else -> value
    }
}
