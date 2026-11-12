package net.azisaba.jg.api.runtime

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack

/** Scratch-like value conversions shared by all blocks. */
object Values {
    @JvmStatic
    fun toNumber(o: Any?): Double = when (o) {
        null -> 0.0
        is Number -> o.toDouble().let { if (it.isNaN()) 0.0 else it }
        is Boolean -> if (o) 1.0 else 0.0
        is String -> o.trim().toDoubleOrNull()?.let { if (it.isNaN()) 0.0 else it } ?: 0.0
        else -> 0.0
    }

    @JvmStatic
    fun toBoolean(o: Any?): Boolean = when (o) {
        null -> false
        is Boolean -> o
        is Number -> o.toDouble() != 0.0
        is String -> !(o.isEmpty() || o == "0" || o.equals("false", ignoreCase = true))
        is GamePlayer -> o.isValid
        else -> true
    }

    /** Text for display. Integral numbers print without ".0"; player names are tag-escaped. */
    @JvmStatic
    fun toText(o: Any?): String = when (o) {
        null -> ""
        is String -> o
        is Double -> formatNumber(o)
        is Float -> formatNumber(o.toDouble())
        is Number -> o.toString()
        is Boolean -> o.toString()
        is GamePlayer -> MiniMessage.miniMessage().escapeTags(o.name)
        is GameTeam -> o.displayName
        is Region -> o.name
        is Material -> o.key.toString()
        is ItemStack -> "${o.type.key} x${o.amount}"
        is Location -> "${formatNumber(o.x)}, ${formatNumber(o.y)}, ${formatNumber(o.z)}"
        is Entity -> o.type.key.toString()
        is List<*> -> o.joinToString(" ") { toText(it) }
        else -> o.toString()
    }

    @JvmStatic
    fun formatNumber(d: Double): String = when {
        d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
        d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
        else -> d.toString()
    }

    /** Scratch equality: numeric if both look numeric, else case-insensitive text; identity for objects. */
    @JvmStatic
    fun equal(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a is GamePlayer && b is GamePlayer) return a.uuid == b.uuid
        if (a is GameTeam && b is GameTeam) return a.id == b.id
        if (isNumeric(a) && isNumeric(b)) return toNumber(a) == toNumber(b)
        if (a is Material || b is Material) return toMaterial(a) == toMaterial(b)
        if (a is Location && b is Location) return a == b
        return toText(a).equals(toText(b), ignoreCase = true)
    }

    @JvmStatic
    fun compare(a: Any?, b: Any?): Int =
        if (isNumeric(a) && isNumeric(b)) toNumber(a).compareTo(toNumber(b))
        else toText(a).compareTo(toText(b), ignoreCase = true)

    @JvmStatic
    fun isNumeric(o: Any?): Boolean = when (o) {
        is Number, is Boolean -> true
        is String -> o.isNotBlank() && o.trim().toDoubleOrNull() != null
        else -> false
    }

    @JvmStatic
    fun toMaterial(o: Any?): Material? = when (o) {
        is Material -> o
        is ItemStack -> o.type
        is String -> parseMaterial(o)
        else -> null
    }

    @JvmStatic
    fun parseMaterial(s: String): Material? {
        val t = s.trim().lowercase()
        if (NamespacedKey.fromString(if (':' in t) t else "minecraft:$t") == null) return null
        // enum lookup: works without a running server (compiler / tests); legacy names excluded
        return Material.matchMaterial(t)
    }

    /** Locations are implicitly bound to the instance world (spec §5.5). */
    @JvmStatic
    fun toLocation(o: Any?, world: World): Location? = when (o) {
        is Location -> o.clone().also { it.world = world }
        is GamePlayer -> o.player?.location
        is Entity -> if (o.isValid) o.location else null
        else -> null
    }

    @JvmStatic
    fun toList(o: Any?): List<Any?> = when (o) {
        is List<*> -> o
        null -> emptyList()
        else -> listOf(o)
    }

    /** Lists are values: assigning one never aliases. */
    @JvmStatic
    fun copyIfList(o: Any?): Any? = if (o is List<*>) ArrayList(o) else o
}
