package net.azisaba.jg.core.block.std

import net.azisaba.jg.api.block.Args
import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.api.type.ValueType
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import java.time.Duration
import kotlin.math.roundToLong

/** `player.*`. Every operation on an invalid (left) player is a silent no-op (spec §5.5). */
internal object PlayerBlocks {
    private val MM = MiniMessage.miniMessage()

    fun all(): List<BlockDefinition> = listOf(
        BlockBuilder.stack("player.teleport").category(Category.PLAYER)
            .label("%player を %location にテレポート", "teleport %player to %location")
            .playerInput("player").input("location", ValueType.LOCATION)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                a.location("location")?.let { p.teleportAsync(it) }
            },

        BlockBuilder.stack("player.teleport_random").category(Category.PLAYER)
            .label("%player を %locations のどれかにテレポート", "teleport %player to random of %locations")
            .playerInput("player").input("locations", ValueType.listOf(ValueType.LOCATION))
            .exec { ex, a ->
                val p = online(a) ?: return@exec
                val list = a.list("locations")
                if (list.isEmpty()) return@exec
                Values.toLocation(list[ex.game.random.nextInt(list.size)], ex.game.world)?.let { p.teleportAsync(it) }
            },

        BlockBuilder.stack("player.give_item").category(Category.PLAYER)
            .label("%player に %item を与える", "give %item to %player")
            .playerInput("player").input("item", ValueType.ITEM)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                (a.get("item") as? ItemStack)?.let { p.inventory.addItem(it.clone()) }
            },

        BlockBuilder.stack("player.remove_item").category(Category.PLAYER)
            .label("%player から %material を %count 個消す", "remove %count %material from %player")
            .playerInput("player").input("material", ValueType.MATERIAL).input("count", ValueType.NUMBER, 1)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                val m = a.material("material") ?: return@exec
                val count = a.integer("count")
                if (count > 0) p.inventory.removeItem(ItemStack(m, count))
            },

        BlockBuilder.stack("player.clear_inventory").category(Category.PLAYER)
            .label("%player のインベントリを空にする", "clear inventory of %player")
            .playerInput("player")
            .exec { _, a -> online(a)?.inventory?.clear() },

        BlockBuilder.stack("player.apply_kit").category(Category.PLAYER)
            .label("%player にキット %kit を適用する", "apply kit %kit to %player")
            .playerInput("player").field(FieldSpec.of("kit", FieldSpec.Kind.KIT))
            .exec { ex, a ->
                val p = online(a) ?: return@exec
                p.inventory.clear()
                ex.game.kit(a.field("kit")).forEach { p.inventory.addItem(it) }
            },

        BlockBuilder.stack("player.set_health").category(Category.PLAYER)
            .label("%player の体力を %value にする", "set health of %player to %value")
            .playerInput("player").input("value", ValueType.NUMBER, 20)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                val cap = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                p.health = a.number("value").coerceIn(0.0, cap)
            },

        BlockBuilder.stack("player.set_food").category(Category.PLAYER)
            .label("%player の満腹度を %value にする", "set food of %player to %value")
            .playerInput("player").input("value", ValueType.NUMBER, 20)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                p.foodLevel = a.integer("value").coerceIn(0, 20)
                p.saturation = 5f
            },

        BlockBuilder.stack("player.add_effect").category(Category.PLAYER)
            .label("%player に %effect を %seconds 秒（レベル %level）与える", "give %effect to %player for %seconds s (level %level)")
            .playerInput("player").field(FieldSpec.text("effect", "speed"))
            .input("seconds", ValueType.NUMBER, 10).input("level", ValueType.NUMBER, 1)
            .exec { ex, a ->
                val p = online(a) ?: return@exec
                val name = a.field("effect").lowercase()
                val type = NamespacedKey.fromString(if (':' in name) name else "minecraft:$name")?.let { Registry.MOB_EFFECT.get(it) }
                if (type == null) {
                    ex.warn("不明なポーション効果です: $name")
                    return@exec
                }
                val ticks = (a.number("seconds") * 20).roundToLong().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                p.addPotionEffect(PotionEffect(type, ticks, (a.integer("level") - 1).coerceIn(0, 255)))
            },

        BlockBuilder.stack("player.clear_effects").category(Category.PLAYER)
            .label("%player のポーション効果を消す", "clear effects of %player")
            .playerInput("player")
            .exec { _, a ->
                val p = online(a) ?: return@exec
                p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
            },

        BlockBuilder.stack("player.set_gamemode").category(Category.PLAYER)
            .label("%player のゲームモードを %mode にする", "set game mode of %player to %mode")
            .playerInput("player").dropdown("mode", "adventure", "adventure", "survival", "creative", "spectator")
            .exec { _, a -> online(a)?.gameMode = GameMode.valueOf(a.field("mode").uppercase()) },

        BlockBuilder.stack("player.launch").category(Category.PLAYER)
            .label("%player を (%x, %y, %z) の勢いで飛ばす", "launch %player by (%x, %y, %z)")
            .playerInput("player").input("x", ValueType.NUMBER, 0).input("y", ValueType.NUMBER, 1).input("z", ValueType.NUMBER, 0)
            .exec { _, a ->
                online(a)?.velocity = Vector(clamp(a.number("x")), clamp(a.number("y")), clamp(a.number("z")))
            },

        BlockBuilder.stack("player.send_message").category(Category.PLAYER)
            .label("%player に %text と言う", "tell %player %text")
            .playerInput("player").input("text", ValueType.STRING, "こんにちは")
            .exec { _, a -> online(a)?.sendMessage(MM.deserialize(a.string("text"))) },

        BlockBuilder.stack("player.send_title").category(Category.PLAYER)
            .label("%player にタイトル %title サブタイトル %subtitle を表示", "show title %title %subtitle to %player")
            .playerInput("player").input("title", ValueType.STRING, "").input("subtitle", ValueType.STRING, "")
            .exec { _, a -> online(a)?.showTitle(title(a.string("title"), a.string("subtitle"))) },

        BlockBuilder.stack("player.send_actionbar").category(Category.PLAYER)
            .label("%player のアクションバーに %text を表示", "show %text in action bar of %player")
            .playerInput("player").input("text", ValueType.STRING, "")
            .exec { _, a -> online(a)?.sendActionBar(MM.deserialize(a.string("text"))) },

        BlockBuilder.stack("player.play_sound").category(Category.PLAYER)
            .label("%player に音 %sound を鳴らす（音量 %volume 高さ %pitch）", "play sound %sound to %player")
            .playerInput("player").field(FieldSpec.text("sound", "minecraft:entity.experience_orb.pickup"))
            .input("volume", ValueType.NUMBER, 1).input("pitch", ValueType.NUMBER, 1)
            .exec { _, a ->
                val p = online(a) ?: return@exec
                val k = a.field("sound")
                p.playSound(
                    Sound.sound(
                        Key.key(if (':' in k) k else "minecraft:$k"), Sound.Source.MASTER,
                        a.number("volume").coerceIn(0.0, 10.0).toFloat(), a.number("pitch").coerceIn(0.5, 2.0).toFloat(),
                    ),
                )
            },

        // ---- reporters ----
        BlockBuilder.reporter("player.location", ValueType.LOCATION).category(Category.PLAYER)
            .label("%player の位置", "location of %player").playerInput("player")
            .eval { _, a -> online(a)?.location },
        BlockBuilder.reporter("player.name", ValueType.STRING).category(Category.PLAYER)
            .label("%player の名前", "name of %player").playerInput("player")
            .eval { _, a -> a.player("player")?.let { MM.escapeTags(it.name) } ?: "" },
        BlockBuilder.reporter("player.team", ValueType.TEAM).category(Category.PLAYER)
            .label("%player のチーム", "team of %player").playerInput("player")
            .eval { _, a -> a.player("player")?.team },
        BlockBuilder.reporter("player.health", ValueType.NUMBER).category(Category.PLAYER)
            .label("%player の体力", "health of %player").playerInput("player")
            .eval { _, a -> online(a)?.health ?: 0.0 },
        BlockBuilder.reporter("player.food", ValueType.NUMBER).category(Category.PLAYER)
            .label("%player の満腹度", "food of %player").playerInput("player")
            .eval { _, a -> online(a)?.foodLevel?.toDouble() ?: 0.0 },
        BlockBuilder.reporter("player.held_item", ValueType.MATERIAL).category(Category.PLAYER)
            .label("%player が持っているアイテムの種類", "held item of %player").playerInput("player")
            .eval { _, a -> online(a)?.inventory?.itemInMainHand?.type },
        BlockBuilder.bool("player.is_valid").category(Category.PLAYER)
            .label("%player がまだ参加している", "%player is still playing").playerInput("player")
            .eval { _, a -> a.player("player")?.isValid ?: false },
        BlockBuilder.bool("player.is_sneaking").category(Category.PLAYER)
            .label("%player がスニークしている", "%player is sneaking").playerInput("player")
            .eval { _, a -> online(a)?.isSneaking ?: false },
    )

    private fun online(a: Args): Player? = a.player("player")?.player

    private fun title(t: String, s: String) = Title.title(
        MM.deserialize(t), MM.deserialize(s),
        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500)),
    )

    private fun clamp(v: Double) = v.coerceIn(-10.0, 10.0)
}
