package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.runtime.ScriptError
import net.azisaba.jg.api.type.ValueType
import net.azisaba.jg.platform.StructureProvider
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/** `world.*`. Large edits and entity spawns are limited per instance (spec §7.4). */
internal object WorldBlocks {
    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.stack("world.set_block").category(Category.WORLD)
            .label("%location に %block を置く", "set block at %location to %block")
            .input("location", ValueType.LOCATION).input("block", ValueType.MATERIAL, "minecraft:stone")
            .exec { _, a ->
                val loc = a.location("location") ?: return@exec
                val m = a.material("block") ?: return@exec
                if (!m.isBlock) throw ScriptError("${m.key} はブロックではありません")
                if (loaded(loc)) loc.block.setType(m, false)
            },

        BlockBuilder.stack("world.break_block").category(Category.WORLD)
            .label("%location のブロックを消す", "remove block at %location")
            .input("location", ValueType.LOCATION)
            .exec { _, a ->
                val loc = a.location("location") ?: return@exec
                if (loaded(loc)) loc.block.setType(Material.AIR, false)
            },

        BlockBuilder.stack("world.fill_region").category(Category.WORLD)
            .label("%region を %block で塗る", "fill %region with %block")
            .input("region", ValueType.REGION).input("block", ValueType.MATERIAL, "minecraft:stone")
            .exec { ex, a ->
                val r = a.region("region") ?: return@exec
                val m = a.material("block") ?: return@exec
                if (!m.isBlock) throw ScriptError("${m.key} はブロックではありません")
                val sp = ex.game.provider(StructureProvider::class.java) ?: throw ScriptError("structure 能力がありません")
                // large fills are split over ticks by the provider
                r.boxes.forEach { sp.fill(r.world, it, m.createBlockData(), ex.game.limits.maxFillBlocksPerTick) }
            },

        BlockBuilder.stack("world.spawn_entity").category(Category.WORLD)
            .label("%location に %type を召喚する", "spawn %type at %location")
            .field(FieldSpec.text("type", "minecraft:zombie"))
            .input("location", ValueType.LOCATION)
            .exec { ex, a ->
                val type = entityType(a.field("type"))
                if (type == null || !type.isSpawnable || type == EntityType.PLAYER) {
                    throw ScriptError("召喚できないエンティティです: ${a.field("type")}")
                }
                a.location("location")?.let { ex.game.spawnTracked(it, type) }
            },

        BlockBuilder.stack("world.remove_entity").category(Category.WORLD)
            .label("%entity を消す", "remove %entity")
            .input("entity", ValueType.ENTITY)
            .exec { ex, a ->
                val e = a.get("entity") as? Entity ?: return@exec
                if (e !is Player && e.isValid && e.world == ex.game.world) e.remove()
            },

        BlockBuilder.reporter("world.entities_in", ValueType.listOf(ValueType.ENTITY)).category(Category.WORLD)
            .label("%region にいるエンティティ（プレイヤー以外）", "entities in %region")
            .input("region", ValueType.REGION)
            .eval { _, a ->
                val r = a.region("region") ?: return@eval ArrayList<Any?>()
                r.world.entities.filterTo(ArrayList<Any?>()) { it !is Player && r.contains(it.location) }
            },

        BlockBuilder.stack("world.explode").category(Category.WORLD)
            .label("%location で威力 %power の爆発（ブロック破壊: %break）", "explode at %location power %power")
            .input("location", ValueType.LOCATION).input("power", ValueType.NUMBER, 2)
            .dropdown("break", "false", "false", "true")
            .exec { _, a ->
                val loc = a.location("location") ?: return@exec
                loc.world.createExplosion(loc, a.number("power").coerceIn(0.0, 8.0).toFloat(), false, a.field("break") == "true")
            },

        BlockBuilder.reporter("world.block_at", ValueType.MATERIAL).category(Category.WORLD)
            .label("%location のブロックの種類", "block at %location")
            .input("location", ValueType.LOCATION)
            .eval { _, a -> a.location("location")?.takeIf { loaded(it) }?.block?.type ?: Material.AIR },

        BlockBuilder.bool("world.in_region").category(Category.WORLD)
            .label("%location が %region の中にある", "%location is in %region")
            .input("location", ValueType.LOCATION).input("region", ValueType.REGION)
            .eval { _, a ->
                val loc = a.location("location")
                a.region("region")?.contains(loc) ?: false
            },

        BlockBuilder.reporter("world.location", ValueType.LOCATION).category(Category.WORLD)
            .label("座標 (%x, %y, %z)", "location (%x, %y, %z)")
            .input("x", ValueType.NUMBER, 0).input("y", ValueType.NUMBER, 64).input("z", ValueType.NUMBER, 0)
            .eval { ex, a -> Location(ex.game.world, a.number("x"), a.number("y"), a.number("z")) },

        BlockBuilder.reporter("world.offset", ValueType.LOCATION).category(Category.WORLD)
            .label("%location から (%dx, %dy, %dz) ずらした位置", "%location offset by (%dx, %dy, %dz)")
            .input("location", ValueType.LOCATION)
            .input("dx", ValueType.NUMBER, 0).input("dy", ValueType.NUMBER, 0).input("dz", ValueType.NUMBER, 0)
            .eval { _, a -> a.location("location")?.add(a.number("dx"), a.number("dy"), a.number("dz")) },

        BlockBuilder.reporter("world.coordinate", ValueType.NUMBER).category(Category.WORLD)
            .label("%location の %axis 座標", "%axis of %location")
            .input("location", ValueType.LOCATION).dropdown("axis", "x", "x", "y", "z", "yaw", "pitch")
            .eval { _, a ->
                val loc = a.location("location") ?: return@eval 0.0
                when (a.field("axis")) {
                    "y" -> loc.y
                    "z" -> loc.z
                    "yaw" -> loc.yaw.toDouble()
                    "pitch" -> loc.pitch.toDouble()
                    else -> loc.x
                }
            },

        BlockBuilder.reporter("world.distance", ValueType.NUMBER).category(Category.WORLD)
            .label("%a と %b の距離", "distance between %a and %b")
            .input("a", ValueType.LOCATION).input("b", ValueType.LOCATION)
            .eval { _, a ->
                val x = a.location("a")
                val y = a.location("b")
                if (x == null || y == null) 0.0 else x.distance(y)
            },

        BlockBuilder.reporter("world.random_location_in", ValueType.LOCATION).category(Category.WORLD)
            .label("%region の中のランダムな位置", "random location in %region")
            .input("region", ValueType.REGION)
            .eval { ex, a -> a.region("region")?.randomLocation(ex.game.random) },

        BlockBuilder.reporter("world.block_below", ValueType.LOCATION).category(Category.WORLD)
            .label("%location の真下のブロックの位置", "block below %location")
            .input("location", ValueType.LOCATION)
            .eval { _, a ->
                a.location("location")?.let { Location(it.world, it.blockX.toDouble(), it.blockY - 1.0, it.blockZ.toDouble()) }
            },
    )

    private fun loaded(loc: Location) = loc.world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)

    private fun entityType(name: String): EntityType? {
        val k = name.lowercase()
        return NamespacedKey.fromString(if (':' in k) k else "minecraft:$k")?.let { Registry.ENTITY_TYPE.get(it) }
    }
}
