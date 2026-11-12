package net.azisaba.jg.core.util

import com.google.gson.JsonElement
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.azisaba.jg.api.runtime.Values
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/**
 * Item literal: `"minecraft:stone"` or
 * `{"material": "...", "amount": 1, "name": "<red>Sword", "lore": ["..."], "enchantments": {"sharpness": 2}, "unbreakable": true}`.
 */
object ItemParser {
    fun material(e: JsonElement): Material {
        val name = when {
            e.isJsonPrimitive -> e.asString
            e.isJsonObject && e.asJsonObject.has("material") -> e.asJsonObject["material"].asString
            else -> throw IllegalArgumentException("material がありません")
        }
        return Values.parseMaterial(name) ?: throw IllegalArgumentException("不明な素材です: $name")
    }

    fun parse(e: JsonElement): ItemStack {
        val m = material(e)
        require(m.isItem) { "アイテムにできない素材です: ${m.key}" }
        if (e.isJsonPrimitive) return ItemStack(m)
        val o = e.asJsonObject
        val amount = (o["amount"] ?: o["count"])?.asInt ?: 1
        val item = ItemStack(m, amount.coerceIn(1, 99))
        val mm = MiniMessage.miniMessage()
        item.editMeta { meta ->
            o["name"]?.let { meta.displayName(mm.deserialize(it.asString).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) }
            o["lore"]?.takeIf { it.isJsonArray }?.let { lore ->
                meta.lore(lore.asJsonArray.map { mm.deserialize(it.asString).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
            }
            o["enchantments"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, lv) ->
                val key = NamespacedKey.fromString(if (':' in k) k else "minecraft:$k")
                val ench = key?.let { RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(it) }
                    ?: throw IllegalArgumentException("不明なエンチャントです: $k")
                meta.addEnchant(ench, lv.asInt, true)
            }
            o["unbreakable"]?.let { meta.isUnbreakable = it.asBoolean }
        }
        return item
    }
}
