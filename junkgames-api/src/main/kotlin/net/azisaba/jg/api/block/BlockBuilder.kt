package net.azisaba.jg.api.block

import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.GamePlayer
import net.azisaba.jg.api.runtime.GameTeam
import net.azisaba.jg.api.runtime.Node
import net.azisaba.jg.api.runtime.Region
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.SimpleStmt
import net.azisaba.jg.api.runtime.TriggerSpec
import net.azisaba.jg.api.type.ValueType
import org.bukkit.Location
import org.bukkit.Material

/**
 * Lazily evaluated inputs of a [BlockBuilder] block. Inputs are evaluated only when read (so
 * `and`/`or` short-circuit), and each read costs one step.
 */
class Args internal constructor(
    val exec: Exec,
    private val inputs: Map<String, Expr?>,
    private val fields: Map<String, String>,
) {
    private fun expr(name: String): Expr = inputs[name] ?: throw ScriptError("input '$name' is empty")

    fun has(name: String) = inputs[name] != null
    fun get(name: String): Any? = inputs[name]?.let { exec.eval(it) }
    fun number(name: String) = exec.number(expr(name))
    fun integer(name: String) = exec.integer(expr(name))
    fun string(name: String) = exec.string(expr(name))
    fun bool(name: String) = exec.bool(expr(name))
    fun player(name: String): GamePlayer? = inputs[name]?.let { exec.player(it) }
    fun team(name: String): GameTeam? = inputs[name]?.let { exec.team(it) }
    fun location(name: String): Location? = inputs[name]?.let { exec.location(it) }
    fun region(name: String): Region? = inputs[name]?.let { exec.region(it) }
    fun material(name: String): Material? = inputs[name]?.let { exec.material(it) }
    fun list(name: String): List<Any?> = exec.list(expr(name))
    fun field(name: String): String = fields[name] ?: throw ScriptError("field '$name' is empty")
}

/**
 * Fluent builder for block definitions.
 *
 * ```kotlin
 * BlockBuilder.stack("player.teleport")
 *     .category(Category.PLAYER)
 *     .label("%player を %location にテレポート")
 *     .playerInput("player")
 *     .input("location", ValueType.LOCATION)
 *     .exec { ex, a -> ... }
 * ```
 */
class BlockBuilder private constructor(
    private val id: String,
    private val shape: BlockShape,
    private val output: ValueType?,
) {
    fun interface Action {
        fun run(ex: Exec, args: Args)
    }

    fun interface Eval {
        fun eval(ex: Exec, args: Args): Any?
    }

    fun interface Compiler {
        @Throws(CompileException::class)
        fun compile(ctx: CompileContext, ir: IrNode): Node
    }

    fun interface TriggerCompiler {
        @Throws(CompileException::class)
        fun compile(ctx: CompileContext, ir: IrNode): TriggerSpec
    }

    private var version = 1
    private var category = Category.CUSTOM
    private var label = Localizable.of(id)
    private val inputs = ArrayList<InputSpec>()
    private val fields = ArrayList<FieldSpec>()
    private val bodies = ArrayList<String>()
    private val requires = LinkedHashSet<String>()
    private val migrations = ArrayList<Migration>()
    private var owner = "JunkGamesCore"

    init {
        require(id.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+"))) { "bad block id: $id" }
    }

    fun version(v: Int) = apply { version = v }
    fun category(c: Category) = apply { category = c }
    fun label(ja: String) = apply { label = Localizable.of(ja) }
    fun label(ja: String, en: String) = apply { label = Localizable.of(ja, en) }
    fun owner(o: String) = apply { owner = o }
    fun input(spec: InputSpec) = apply { inputs += spec }
    fun input(name: String, type: ValueType) = input(InputSpec.required(name, type))
    fun input(name: String, type: ValueType, defaultLiteral: Any) = input(InputSpec.withDefault(name, type, defaultLiteral))
    fun optionalInput(name: String, type: ValueType) = input(InputSpec.optional(name, type))

    /** Player input defaulting to the event's player ("this player"). */
    fun playerInput(name: String) = input(InputSpec.defaultBlock(name, ValueType.PLAYER, "event.player"))
    fun field(spec: FieldSpec) = apply { fields += spec }
    fun dropdown(name: String, defaultValue: String, vararg options: String) = field(FieldSpec.dropdown(name, defaultValue, *options))
    fun body(name: String) = apply { bodies += name }

    /** "view", "view:GLOW", ... */
    fun requires(requirement: String) = apply { requires += requirement }
    fun migration(m: Migration) = apply { migrations += m }

    // ---- terminal operations ----

    /** STACK / CAP block whose inputs are evaluated lazily through [Args]. */
    fun exec(action: Action): BlockDefinition {
        check(shape.isStatement) { "$id is not a statement" }
        return compiler { ctx, ir ->
            val inp = compileInputs(ctx, ir)
            val f = compileFields(ctx, ir)
            SimpleStmt { ex -> action.run(ex, Args(ex, inp, f)) }
        }
    }

    /** REPORTER / BOOLEAN block. */
    fun eval(eval: Eval): BlockDefinition {
        check(shape.isExpression) { "$id is not a reporter" }
        val out = output!!
        return compiler { ctx, ir ->
            val inp = compileInputs(ctx, ir)
            val f = compileFields(ctx, ir)
            Expr.of(out) { ex -> eval.eval(ex, Args(ex, inp, f)) }
        }
    }

    /** HAT block. */
    fun trigger(compiler: TriggerCompiler): BlockDefinition {
        check(shape == BlockShape.HAT) { "$id is not a hat" }
        return compiler(Compiler { ctx, ir -> compiler.compile(ctx, ir) })
    }

    /** Fully custom compilation. */
    fun compiler(compiler: Compiler): BlockDefinition = Built(
        id, version, shape, inputs.toList(), fields.toList(), bodies.toList(), output, label, category,
        requires.toSet(), owner, compiler, migrations.toList(),
    )

    private fun compileInputs(ctx: CompileContext, ir: IrNode): Map<String, Expr?> =
        inputs.associate { it.name to ctx.input(ir, it.name) }

    private fun compileFields(ctx: CompileContext, ir: IrNode): Map<String, String> =
        fields.associate { it.name to ctx.field(ir, it.name) }

    private class Built(
        override val id: String,
        override val schemaVersion: Int,
        override val shape: BlockShape,
        override val inputs: List<InputSpec>,
        override val fields: List<FieldSpec>,
        override val bodies: List<String>,
        override val output: ValueType?,
        override val label: Localizable,
        override val category: Category,
        override val requires: Set<String>,
        override val owner: String,
        private val compiler: Compiler,
        override val migrations: List<Migration>,
    ) : BlockDefinition {
        override fun compile(ctx: CompileContext, ir: IrNode): Node = compiler.compile(ctx, ir)
    }

    companion object {
        @JvmStatic fun stack(id: String) = BlockBuilder(id, BlockShape.STACK, null)
        @JvmStatic fun cap(id: String) = BlockBuilder(id, BlockShape.CAP, null)
        @JvmStatic fun cBlock(id: String) = BlockBuilder(id, BlockShape.C, null)
        @JvmStatic fun hat(id: String) = BlockBuilder(id, BlockShape.HAT, null)
        @JvmStatic fun reporter(id: String, output: ValueType) = BlockBuilder(id, BlockShape.REPORTER, output)
        @JvmStatic fun bool(id: String) = BlockBuilder(id, BlockShape.BOOLEAN, ValueType.BOOLEAN)
    }
}
