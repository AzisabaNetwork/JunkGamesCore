package net.azisaba.jg.core.compile

import com.google.gson.JsonElement
import net.azisaba.jg.api.block.CompileException
import net.azisaba.jg.api.ir.MapRequirements
import net.azisaba.jg.api.runtime.Exec
import net.azisaba.jg.api.runtime.Expr
import net.azisaba.jg.api.runtime.Signal
import net.azisaba.jg.api.runtime.Stmt
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.core.util.ItemParser
import org.bukkit.Location
import org.bukkit.inventory.ItemStack

/** `{"regionRef": "floor"}` — resolved by name against the instance's map at runtime. */
class RegionRef(val name: String) : Expr {
    override fun eval(ex: Exec): Any? = ex.game.region(name)
    override val type: ValueType get() = ValueType.REGION
}

/** `{"pointRef": "spawn"}`. A `multiple` point is a `list<location>`; otherwise one location. */
class PointRef(val name: String, val multiple: Boolean) : Expr {
    override fun eval(ex: Exec): Any? {
        val pts = ex.game.points(name)
        return if (multiple) ArrayList<Any?>(pts) else pts.firstOrNull()
    }

    override val type: ValueType get() = if (multiple) ValueType.listOf(ValueType.LOCATION) else ValueType.LOCATION
}

/** A statement sequence (IR arrays). */
class Seq(private val items: Array<Stmt>) : Stmt {
    override fun run(ex: Exec, frame: net.azisaba.jg.api.runtime.Frame): Signal {
        if (frame.pc < items.size) {
            ex.push(items[frame.pc++])
            return Signal.PUSHED
        }
        return Signal.DONE
    }

    companion object {
        val EMPTY = Stmt { _, _ -> Signal.DONE }

        fun of(items: List<Stmt>): Stmt = when (items.size) {
            0 -> EMPTY
            1 -> items[0]
            else -> Seq(items.toTypedArray())
        }
    }
}

/** Compiles `{"lit": ...}` values into typed expressions. */
object Literals {
    @Throws(CompileException::class)
    fun compile(lit: JsonElement?, expected: ValueType, req: MapRequirements): Expr {
        val e = infer(lit, expected, req)
        if (!expected.accepts(e.type)) {
            throw CompileException("型が合いません: ${expected.id} が必要ですが、値は ${e.type.id} です")
        }
        return e
    }

    private fun infer(lit: JsonElement?, expected: ValueType, req: MapRequirements): Expr {
        if (lit == null || lit.isJsonNull) return Expr.constant(ValueType.ANY, null)
        if (lit.isJsonPrimitive) {
            val p = lit.asJsonPrimitive
            if (p.isBoolean) return Expr.constant(ValueType.BOOLEAN, p.asBoolean)
            if (p.isNumber) {
                val d = p.asDouble
                return if (expected.kind == ValueType.Kind.STRING) Expr.constant(ValueType.STRING, Values.formatNumber(d))
                else Expr.constant(ValueType.NUMBER, d)
            }
            val s = p.asString
            when (expected.kind) {
                ValueType.Kind.MATERIAL -> return Expr.constant(ValueType.MATERIAL, material(lit))
                ValueType.Kind.ITEM -> return item(lit)
                ValueType.Kind.NUMBER ->
                    return if (Values.isNumeric(s)) Expr.constant(ValueType.NUMBER, Values.toNumber(s))
                    else throw CompileException("数値が必要です: \"$s\"")
                ValueType.Kind.BOOLEAN ->
                    if (s.equals("true", true) || s.equals("false", true)) return Expr.constant(ValueType.BOOLEAN, s.toBoolean())
                else -> {}
            }
            return Expr.constant(ValueType.STRING, s)
        }
        if (lit.isJsonArray) {
            val elem = (expected as? ValueType.ListOf)?.element ?: ValueType.ANY
            val items = lit.asJsonArray.map { compile(it, elem, req) }
            return Expr.of(ValueType.listOf(elem)) { ex -> items.mapTo(ArrayList(items.size)) { ex.eval(it) } }
        }
        val o = lit.asJsonObject
        o["regionRef"]?.let {
            val name = it.asString
            if (name !in req.regions) throw CompileException("マップ要件に領域 '$name' が宣言されていません")
            return RegionRef(name)
        }
        o["pointRef"]?.let {
            val name = it.asString
            val pr = req.points[name] ?: throw CompileException("マップ要件に地点 '$name' が宣言されていません")
            // a "multiple" point used where one location is expected resolves to the first one
            return PointRef(name, pr.multiple && expected.kind != ValueType.Kind.LOCATION)
        }
        if (o.has("item") || (expected.kind == ValueType.Kind.ITEM && o.has("material"))) {
            return item(o["item"] ?: o)
        }
        if (o.has("material")) return Expr.constant(ValueType.MATERIAL, material(o))
        if (o.has("x") && o.has("y") && o.has("z")) {
            val loc = Location(
                null, o["x"].asDouble, o["y"].asDouble, o["z"].asDouble,
                o["yaw"]?.asFloat ?: 0f, o["pitch"]?.asFloat ?: 0f,
            )
            // Values.toLocation binds the instance world when the value is used
            return Expr.of(ValueType.LOCATION) { loc.clone() }
        }
        val s = o.toString()
        throw CompileException("解釈できないリテラルです: ${if (s.length > 80) s.take(80) + "…" else s}")
    }

    private fun material(e: JsonElement) = try {
        ItemParser.material(e)
    } catch (ex: IllegalArgumentException) {
        throw CompileException(ex.message ?: "bad material")
    }

    private fun item(e: JsonElement): Expr {
        val prototype: ItemStack = try {
            ItemParser.parse(e)
        } catch (ex: IllegalArgumentException) {
            throw CompileException(ex.message ?: "bad item")
        }
        return Expr.of(ValueType.ITEM) { prototype.clone() }
    }
}
