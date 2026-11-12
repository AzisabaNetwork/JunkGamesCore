package net.azisaba.jg.api.block

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.azisaba.jg.api.ir.GameMeta
import net.azisaba.jg.api.ir.IrCustomBlock
import net.azisaba.jg.api.ir.IrNode
import net.azisaba.jg.api.ir.IrVariable
import net.azisaba.jg.api.ir.MapRequirements
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Limits
import net.azisaba.jg.api.runtime.Node
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.type.ValueType
import org.bukkit.plugin.Plugin

/** Block shapes (spec §6.1). */
enum class BlockShape {
    /** "when ..." – starts a script. */
    HAT,

    /** Ordinary statement. */
    STACK,

    /** Statement with inner substacks ("if", "repeat"). */
    C,

    /** Round reporter – returns a value. */
    REPORTER,

    /** Hexagonal reporter – returns a boolean. */
    BOOLEAN,

    /** Terminal statement – nothing may follow ("stop script"). */
    CAP;

    val isStatement: Boolean get() = this == STACK || this == C || this == CAP
    val isExpression: Boolean get() = this == REPORTER || this == BOOLEAN
}

/** Text with translations. Default language: ja. */
data class Localizable(val translations: Map<String, String>) {
    operator fun get(lang: String): String = translations[lang] ?: translations["ja"] ?: translations.values.first()

    companion object {
        @JvmStatic
        fun of(ja: String) = Localizable(mapOf("ja" to ja))

        @JvmStatic
        fun of(ja: String, en: String) = Localizable(linkedMapOf("ja" to ja, "en" to en))
    }
}

/** Toolbox category. Add-ons may create their own. */
data class Category(val id: String, val color: String, val label: Localizable) {
    companion object {
        @JvmField val EVENT = Category("event", "#FFBF00", Localizable.of("イベント", "Events"))
        @JvmField val CONTROL = Category("control", "#FFAB19", Localizable.of("制御", "Control"))
        @JvmField val GAME = Category("game", "#FF6680", Localizable.of("ゲーム", "Game"))
        @JvmField val PLAYER = Category("player", "#4C97FF", Localizable.of("プレイヤー", "Player"))
        @JvmField val WORLD = Category("world", "#59C059", Localizable.of("ワールド", "World"))
        @JvmField val UI = Category("ui", "#9966FF", Localizable.of("表示", "Display"))
        @JvmField val VIEW = Category("view", "#CF63CF", Localizable.of("見え方", "View"))
        @JvmField val VARIABLE = Category("var", "#FF8C1A", Localizable.of("変数", "Variables"))
        @JvmField val OPERATOR = Category("op", "#40BF4A", Localizable.of("演算", "Operators"))
        @JvmField val LIST = Category("list", "#FF661A", Localizable.of("リスト", "Lists"))
        @JvmField val CUSTOM = Category("custom", "#FF6680", Localizable.of("自作", "My Blocks"))
    }
}

/**
 * A value input.
 *
 * @property defaultLiteral literal used when the input is empty (also the editor's shadow value)
 * @property defaultBlock block type used when the input is empty (e.g. `event.player`)
 * @property optional empty is allowed and yields null
 */
data class InputSpec(
    val name: String,
    val type: ValueType,
    val defaultLiteral: JsonElement? = null,
    val defaultBlock: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        fun required(name: String, type: ValueType) = InputSpec(name, type)

        @JvmStatic
        fun withDefault(name: String, type: ValueType, literal: Any) = InputSpec(
            name, type, when (literal) {
                is JsonElement -> literal
                is Number -> JsonPrimitive(literal)
                is Boolean -> JsonPrimitive(literal)
                else -> JsonPrimitive(literal.toString())
            },
        )

        @JvmStatic
        fun defaultBlock(name: String, type: ValueType, blockType: String) = InputSpec(name, type, defaultBlock = blockType)

        @JvmStatic
        fun optional(name: String, type: ValueType) = InputSpec(name, type, optional = true)
    }
}

/** A constant field (dropdown, text, ...). For DROPDOWN, [options] are validated by the compiler. */
data class FieldSpec(val name: String, val kind: Kind, val options: List<String> = emptyList(), val defaultValue: String? = null) {
    enum class Kind {
        DROPDOWN, TEXT, NUMBER, CHECKBOX,

        /** Name of a declared variable (or loop / parameter local). */
        VARIABLE,

        /** Name of a broadcast message. */
        MESSAGE,

        /** Name of a custom block. */
        CUSTOM_BLOCK,

        /** Name of a kit in meta.kits. */
        KIT,
    }

    companion object {
        @JvmStatic
        fun dropdown(name: String, defaultValue: String, vararg options: String) = FieldSpec(name, Kind.DROPDOWN, options.toList(), defaultValue)

        @JvmStatic
        fun text(name: String, defaultValue: String?) = FieldSpec(name, Kind.TEXT, emptyList(), defaultValue)

        @JvmStatic
        fun of(name: String, kind: Kind) = FieldSpec(name, kind)
    }
}

/** Upgrades one block's IR JSON from [fromVersion] to `fromVersion + 1`; applied at load (spec §15). */
interface Migration {
    val fromVersion: Int

    /** May mutate and return [node]. Should set "v". */
    fun migrate(node: JsonObject): JsonObject
}

/** Thrown by [BlockDefinition.compile]; reported as an error on that node. */
class CompileException(message: String) : Exception(message, null, false, false)

/**
 * A block type (spec §6.3). Registered by the core and by add-ons through [BlockRegistry].
 * The editor's block definitions are generated from these — never written by hand.
 * Most implementations should use [BlockBuilder].
 */
interface BlockDefinition {
    /** "namespace.name", e.g. `player.teleport`. */
    val id: String
    val schemaVersion: Int
    val shape: BlockShape
    val inputs: List<InputSpec>
    val fields: List<FieldSpec>

    /** Names of substacks for C blocks ("do", "else"). */
    val bodies: List<String>

    /** Declared output type for reporters (null for statements / hats). */
    val output: ValueType?

    /** Label with `%name` placeholders for inputs/fields/bodies. */
    val label: Localizable
    val category: Category

    /** Capability requirements: "view", "view:GLOW", ... (spec §12.3). */
    val requires: Set<String>

    /** Plugin that registered the block. */
    val owner: String

    /**
     * Compiles the IR node. Must return an [Expr] for reporters, a [Stmt] for statements and a
     * [net.azisaba.jg.api.runtime.TriggerSpec] for hats.
     */
    @Throws(CompileException::class)
    fun compile(ctx: CompileContext, ir: IrNode): Node

    /** Migrations from older schema versions. */
    val migrations: List<Migration>

    fun input(name: String): InputSpec? = inputs.firstOrNull { it.name == name }
    fun field(name: String): FieldSpec? = fields.firstOrNull { it.name == name }
}

/**
 * Registry of block types. Add-ons register blocks in `onEnable` (declare `depend: [JunkGamesCore]`);
 * the core recompiles loaded games when the registry changes.
 */
interface BlockRegistry {
    fun register(owner: Plugin, definition: BlockDefinition)

    /** Unregisters all blocks of a plugin (also done automatically on plugin disable). */
    fun unregisterAll(owner: Plugin)
    operator fun get(id: String): BlockDefinition?
    fun all(): Collection<BlockDefinition>
}

/** Services available while compiling one game. */
interface CompileContext {
    /**
     * Compiles the input [name] of [node] according to the block's [InputSpec]: applies defaults,
     * compiles literals and type-checks the result. Returns null only for empty optional inputs.
     */
    @Throws(CompileException::class)
    fun input(node: IrNode, name: String): Expr?

    /** Same as [input] with an explicit expected type. */
    @Throws(CompileException::class)
    fun input(node: IrNode, name: String, expected: ValueType): Expr?

    /** Compiles the substack [name] into a single statement (empty → no-op). */
    fun body(node: IrNode, name: String): Stmt

    /** Field value, falling back to the FieldSpec default. Dropdown values are validated. */
    @Throws(CompileException::class)
    fun field(node: IrNode, name: String): String

    val meta: GameMeta
    val mapRequirements: MapRequirements
    fun variable(name: String): IrVariable?
    fun customBlock(name: String): IrCustomBlock?

    /** Type of a loop variable / custom block parameter visible here, or null. */
    fun localType(name: String): ValueType?
    fun pushLocal(name: String, type: ValueType)
    fun popLocal()

    /** True inside the body of a custom reporter (waiting is not allowed there). */
    val inReporterBody: Boolean
    fun warning(node: IrNode, message: String)
    val limits: Limits
}
