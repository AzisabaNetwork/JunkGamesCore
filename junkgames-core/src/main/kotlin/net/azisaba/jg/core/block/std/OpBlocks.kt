package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/** `op.*`: arithmetic, comparison, logic and text (Scratch-equivalent). */
internal object OpBlocks {
    fun all(maxStringLength: Int): List<BlockDefinition> = listOf(
        arith("op.add", "%a + %b") { a, b -> a + b },
        arith("op.sub", "%a - %b") { a, b -> a - b },
        arith("op.mul", "%a × %b") { a, b -> a * b },
        arith("op.div", "%a ÷ %b") { a, b -> if (b == 0.0) (if (a == 0.0) 0.0 else Math.copySign(Double.POSITIVE_INFINITY, a)) else a / b },
        arith("op.mod", "%a を %b で割った余り") { a, b ->
            if (b == 0.0) 0.0 else {
                val r = a % b
                if (r != 0.0 && (r < 0) != (b < 0)) r + b else r // sign follows divisor (Scratch)
            }
        },

        BlockBuilder.reporter("op.random", ValueType.NUMBER).category(Category.OPERATOR)
            .label("%from から %to までの乱数", "pick random %from to %to")
            .input("from", ValueType.NUMBER, 1).input("to", ValueType.NUMBER, 10)
            .eval { ex, a ->
                var lo = a.number("from")
                var hi = a.number("to")
                if (lo > hi) lo = hi.also { hi = lo }
                if (lo == Math.rint(lo) && hi == Math.rint(hi)) {
                    (lo.toLong() + ex.game.random.nextLong(hi.toLong() - lo.toLong() + 1)).toDouble()
                } else lo + ex.game.random.nextDouble() * (hi - lo)
            },

        compare("op.lt", "%a < %b") { it < 0 },
        compare("op.gt", "%a > %b") { it > 0 },
        BlockBuilder.bool("op.eq").category(Category.OPERATOR).label("%a = %b")
            .input("a", ValueType.ANY, "").input("b", ValueType.ANY, "")
            .eval { _, a -> Values.equal(a.get("a"), a.get("b")) },

        BlockBuilder.bool("op.and").category(Category.OPERATOR).label("%a かつ %b", "%a and %b")
            .input("a", ValueType.BOOLEAN, false).input("b", ValueType.BOOLEAN, false)
            .eval { _, a -> a.bool("a") && a.bool("b") },
        BlockBuilder.bool("op.or").category(Category.OPERATOR).label("%a または %b", "%a or %b")
            .input("a", ValueType.BOOLEAN, false).input("b", ValueType.BOOLEAN, false)
            .eval { _, a -> a.bool("a") || a.bool("b") },
        BlockBuilder.bool("op.not").category(Category.OPERATOR).label("%a ではない", "not %a")
            .input("a", ValueType.BOOLEAN, false)
            .eval { _, a -> !a.bool("a") },

        BlockBuilder.reporter("op.join", ValueType.STRING).category(Category.OPERATOR)
            .label("%a と %b", "join %a %b")
            .input("a", ValueType.STRING, "").input("b", ValueType.STRING, "")
            .eval { _, a ->
                val s = a.string("a") + a.string("b")
                if (s.length > maxStringLength) throw ScriptError("文字列が長すぎます（上限 $maxStringLength 文字）")
                s
            },
        BlockBuilder.reporter("op.letter_of", ValueType.STRING).category(Category.OPERATOR)
            .label("%text の %index 番目の文字", "letter %index of %text")
            .input("index", ValueType.NUMBER, 1).input("text", ValueType.STRING, "")
            .eval { _, a ->
                val s = a.string("text")
                val i = a.integer("index")
                if (i in 1..s.length) s[i - 1].toString() else ""
            },
        BlockBuilder.reporter("op.length", ValueType.NUMBER).category(Category.OPERATOR)
            .label("%text の長さ", "length of %text")
            .input("text", ValueType.STRING, "")
            .eval { _, a -> a.string("text").length.toDouble() },
        BlockBuilder.bool("op.contains").category(Category.OPERATOR)
            .label("%text に %part が含まれる", "%text contains %part")
            .input("text", ValueType.STRING, "").input("part", ValueType.STRING, "")
            .eval { _, a -> a.string("text").contains(a.string("part"), ignoreCase = true) },
        BlockBuilder.reporter("op.round", ValueType.NUMBER).category(Category.OPERATOR)
            .label("%a を四捨五入", "round %a")
            .input("a", ValueType.NUMBER, 0)
            .eval { _, a -> Math.round(a.number("a")).toDouble() },
        BlockBuilder.reporter("op.math", ValueType.NUMBER).category(Category.OPERATOR)
            .label("%fn ( %a )")
            .dropdown("fn", "abs", "abs", "floor", "ceiling", "sqrt", "sin", "cos", "tan", "asin", "acos", "atan", "ln", "log", "e^", "10^")
            .input("a", ValueType.NUMBER, 0)
            .eval { _, a ->
                val x = a.number("a")
                val r = when (a.field("fn")) {
                    "abs" -> abs(x)
                    "floor" -> floor(x)
                    "ceiling" -> ceil(x)
                    "sqrt" -> sqrt(x)
                    "sin" -> round(Math.sin(Math.toRadians(x)) * 1e10) / 1e10
                    "cos" -> round(Math.cos(Math.toRadians(x)) * 1e10) / 1e10
                    "tan" -> Math.tan(Math.toRadians(x))
                    "asin" -> Math.toDegrees(Math.asin(x))
                    "acos" -> Math.toDegrees(Math.acos(x))
                    "atan" -> Math.toDegrees(Math.atan(x))
                    "ln" -> Math.log(x)
                    "log" -> Math.log10(x)
                    "e^" -> Math.exp(x)
                    "10^" -> Math.pow(10.0, x)
                    else -> 0.0
                }
                if (r.isNaN()) 0.0 else r
            },
        BlockBuilder.reporter("op.to_number", ValueType.NUMBER).category(Category.OPERATOR)
            .label("%a を数値に", "%a as number")
            .input("a", ValueType.ANY, "0")
            .eval { _, a -> Values.toNumber(a.get("a")) },
    )

    private fun arith(id: String, label: String, op: (Double, Double) -> Double) =
        BlockBuilder.reporter(id, ValueType.NUMBER).category(Category.OPERATOR).label(label)
            .input("a", ValueType.NUMBER, 0).input("b", ValueType.NUMBER, 0)
            .eval { _, a -> op(a.number("a"), a.number("b")) }

    private fun compare(id: String, label: String, test: (Int) -> Boolean) =
        BlockBuilder.bool(id).category(Category.OPERATOR).label(label)
            .input("a", ValueType.ANY, "").input("b", ValueType.ANY, 50)
            .eval { _, a -> test(Values.compare(a.get("a"), a.get("b"))) }
}
