package net.azisaba.jg.core.compile

import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.BlockShape
import net.azisaba.jg.api.block.CompileContext
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.ir.Diagnostic
import net.azisaba.jg.api.ir.GameDefinition
import net.azisaba.jg.api.ir.GameMeta
import net.azisaba.jg.api.ir.IrCustomBlock
import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.ir.MapRequirements
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.api.runtime.Node
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.TriggerSpec
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.runtime.CustomCalls
import java.util.IdentityHashMap

/**
 * IR → executable node tree (spec §2.1 Compiler), including type checking (spec §5.5).
 * One instance compiles one game.
 */
class GameCompiler(
    private val blocks: (String) -> BlockDefinition?,
    override val limits: Limits,
) : CompileContext {
    val diagnostics = ArrayList<Diagnostic>()
    val hasErrors: Boolean get() = diagnostics.any { it.isError }

    private val nodeIds = IdentityHashMap<Node, String>()
    private val capabilities = LinkedHashSet<String>()
    private val owners = LinkedHashSet<String>()
    private val locals = ArrayDeque<Pair<String, ValueType>>()

    private lateinit var def: GameDefinition
    private var variables: Map<String, IrVariable> = emptyMap()
    private var customs: Map<String, CompiledGame.CustomBlock> = emptyMap()
    private var path = "/"
    override var inReporterBody: Boolean = false
        private set

    /** Compiles; returns null when there were errors (see [diagnostics]). */
    fun compile(def: GameDefinition): CompiledGame? {
        this.def = def
        variables = def.variables.associateBy { it.name }
        checkVariableDefaults()
        customs = def.customBlocks.associate { it.name to CompiledGame.CustomBlock(it) }

        def.customBlocks.forEachIndexed { i, c ->
            path = "/customBlocks/$i"
            c.params.forEach { pushLocal(it.name, it.type) }
            inReporterBody = c.returns != null
            customs.getValue(c.name).body = compileSeq(c.body)
            inReporterBody = false
            repeat(c.params.size) { popLocal() }
        }

        val scripts = ArrayList<CompiledGame.Script>()
        def.scripts.forEachIndexed { i, s ->
            path = "/scripts/$i"
            val trigger = compileTrigger(s.trigger)
            val body = compileSeq(s.body)
            if (trigger != null) scripts += CompiledGame.Script(i, s.id, trigger, s.concurrency, body)
        }
        if (hasErrors) return null
        return CompiledGame(
            def, scripts, scripts.groupBy { it.trigger.eventType }, customs, variables,
            nodeIds, capabilities.toSet(), owners.toSet(),
        )
    }

    private fun checkVariableDefaults() {
        def.variables.forEachIndexed { i, v ->
            val d = v.defaultValue ?: return@forEachIndexed
            try {
                Literals.compile(d, v.type, def.mapRequirements)
            } catch (e: CompileException) {
                diagnostics += Diagnostic.error(null, "/variables/$i/default", "${v.name}: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ nodes

    private fun compileTrigger(ir: IrNode): TriggerSpec? {
        if (ir.isLiteral) return null.also { error(ir, "トリガーにはハットブロックが必要です") }
        val d = definition(ir) ?: return null
        if (d.shape != BlockShape.HAT) return null.also { error(ir, "${d.id} はハットブロックではありません") }
        return try {
            val n = d.compile(this, ir)
            (n as? TriggerSpec)?.also { record(ir, it) }
                ?: null.also { error(ir, "${d.id} のコンパイル結果がトリガーではありません（ブロック実装の不具合）") }
        } catch (e: CompileException) {
            null.also { error(ir, e.message ?: "") }
        } catch (e: RuntimeException) {
            null.also { error(ir, "${d.id} のコンパイル中に内部エラー: $e") }
        }
    }

    fun compileSeq(nodes: List<IrNode>): Stmt {
        val out = ArrayList<Stmt>(nodes.size)
        nodes.forEachIndexed { i, ir ->
            out += compileStmt(ir)
            if (i < nodes.size - 1 && !ir.isLiteral && blocks(ir.type)?.shape == BlockShape.CAP) {
                error(nodes[i + 1], "${ir.type} の後ろにはブロックをつなげられません")
            }
        }
        return Seq.of(out)
    }

    fun compileStmt(ir: IrNode): Stmt {
        if (ir.isLiteral) return Seq.EMPTY.also { error(ir, "値は文として置けません") }
        if (ir.type == CUSTOM_CALL) {
            return try {
                val c = customTarget(ir)
                CustomCalls.statement(c, customArgs(ir, c)).also { record(ir, it) }
            } catch (e: CompileException) {
                Seq.EMPTY.also { error(ir, e.message ?: "") }
            }
        }
        val d = definition(ir) ?: return Seq.EMPTY
        if (!d.shape.isStatement) return Seq.EMPTY.also { error(ir, "${d.id} は値ブロックなので、文としては置けません") }
        return try {
            val n = d.compile(this, ir)
            (n as? Stmt)?.also { record(ir, it) }
                ?: Seq.EMPTY.also { error(ir, "${d.id} のコンパイル結果が文ではありません（ブロック実装の不具合）") }
        } catch (e: CompileException) {
            Seq.EMPTY.also { error(ir, e.message ?: "") }
        } catch (e: RuntimeException) {
            Seq.EMPTY.also { error(ir, "${d.id} のコンパイル中に内部エラー: $e") }
        }
    }

    @Throws(CompileException::class)
    fun compileExpr(ir: IrNode, expected: ValueType): Expr {
        if (ir.isLiteral) return Literals.compile(ir.lit, expected, def.mapRequirements)
        val e: Expr = if (ir.type == CUSTOM_CALL) {
            val c = customTarget(ir)
            if (c.ir.returns == null) throw CompileException("自作ブロック '${c.ir.name}' は値を返しません")
            CustomCalls.reporter(c, customArgs(ir, c))
        } else {
            val d = definition(ir) ?: throw CompileException("不明なブロックです: ${ir.type}")
            if (!d.shape.isExpression) throw CompileException("${d.id} は値を返さないため、入力には置けません")
            val n = try {
                d.compile(this, ir)
            } catch (ex: RuntimeException) {
                throw CompileException("${d.id} のコンパイル中に内部エラー: $ex")
            }
            n as? Expr ?: throw CompileException("${d.id} のコンパイル結果が値ではありません（ブロック実装の不具合）")
        }
        if (!expected.accepts(e.type)) {
            throw CompileException("型が合いません: ${expected.id} が必要ですが、${ir.type} は ${e.type.id} を返します")
        }
        record(ir, e)
        return e
    }

    private fun customTarget(ir: IrNode): CompiledGame.CustomBlock {
        val name = ir.fieldString("name") ?: throw CompileException("custom.call に name がありません")
        return customs[name] ?: throw CompileException("自作ブロック '$name' は定義されていません")
    }

    private fun customArgs(ir: IrNode, c: CompiledGame.CustomBlock): Map<String, Expr> {
        val args = LinkedHashMap<String, Expr>()
        for (p in c.ir.params) {
            val inp = ir.inputs[p.name] ?: throw CompileException("引数 '${p.name}' が空です")
            args[p.name] = compileExpr(inp, p.type)
        }
        ir.inputs.keys.filter { it !in args }.forEach { warning(ir, "未使用の引数です: $it") }
        return args
    }

    private fun definition(ir: IrNode): BlockDefinition? {
        val d = blocks(ir.type)
        if (d == null) {
            error(ir, "このサーバーにないブロックです: ${ir.type}（アドオン不足の可能性があります）")
            return null
        }
        capabilities += d.requires
        owners += d.owner
        return d
    }

    private fun record(ir: IrNode, n: Node) {
        ir.id?.let { nodeIds.putIfAbsent(n, it) }
    }

    private fun error(ir: IrNode, message: String) {
        diagnostics += Diagnostic.error(ir.id, path, message)
    }

    // ------------------------------------------------------------------ CompileContext

    override fun input(node: IrNode, name: String): Expr? {
        val d = blocks(node.type) ?: throw CompileException("不明なブロックです: ${node.type}")
        val spec = d.input(name) ?: throw CompileException("${d.id} に入力 '$name' はありません")
        return input(node, name, spec.type)
    }

    override fun input(node: IrNode, name: String, expected: ValueType): Expr? {
        node.inputs[name]?.let { return compileExpr(it, expected) }
        val spec = blocks(node.type)?.input(name)
        if (spec != null) {
            spec.defaultBlock?.let { return compileExpr(IrNode.synthetic(it), expected) }
            spec.defaultLiteral?.let { return Literals.compile(it, expected, def.mapRequirements) }
            if (spec.optional) return null
        }
        throw CompileException("入力 '$name' が空です")
    }

    override fun body(node: IrNode, name: String): Stmt = node.body[name]?.let { compileSeq(it) } ?: Seq.EMPTY

    override fun field(node: IrNode, name: String): String {
        val d = blocks(node.type) ?: throw CompileException("不明なブロックです: ${node.type}")
        val spec = d.field(name)
        val v = node.fieldString(name) ?: spec?.defaultValue ?: throw CompileException("フィールド '$name' が空です")
        when (spec?.kind) {
            FieldSpec.Kind.DROPDOWN -> if (spec.options.isNotEmpty() && v !in spec.options) {
                throw CompileException("フィールド '$name' の値が不正です: $v（${spec.options.joinToString(" / ")}）")
            }
            FieldSpec.Kind.KIT -> if (v !in def.meta.kits) throw CompileException("キット '$v' は meta.kits に定義されていません")
            FieldSpec.Kind.CUSTOM_BLOCK -> if (v !in customs) throw CompileException("自作ブロック '$v' は定義されていません")
            else -> {}
        }
        return v
    }

    override val meta: GameMeta get() = def.meta
    override val mapRequirements: MapRequirements get() = def.mapRequirements
    override fun variable(name: String): IrVariable? = variables[name]
    override fun customBlock(name: String): IrCustomBlock? = customs[name]?.ir
    override fun localType(name: String): ValueType? = locals.lastOrNull { it.first == name }?.second
    override fun pushLocal(name: String, type: ValueType) = locals.addLast(name to type)
    override fun popLocal() {
        locals.removeLast()
    }

    override fun warning(node: IrNode, message: String) {
        diagnostics += Diagnostic.warning(node.id, path, message)
    }

    companion object {
        const val CUSTOM_CALL = "custom.call"
    }
}
