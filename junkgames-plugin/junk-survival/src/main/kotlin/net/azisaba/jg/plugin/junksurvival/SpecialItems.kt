package net.azisaba.jg.plugin.junksurvival

import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The five special items. */
enum class SpecialItem(
    val id: String,
    val number: String,
    val material: Material,
    val displayName: String,
    /** Display name of items made by the old plugin (`&`-codes), still recognized. */
    val legacyName: String,
    val good: Boolean,
) {
    BORDER("border", "1", Material.PAPER, "<green>ワールドボーダー拡張", "§aワールドボーダー拡張", true),
    BOMB("bomb", "2", Material.TNT, "<red>どかーん", "§cどかーん", false),
    JUMP("jump", "3", Material.REDSTONE_TORCH, "<aqua>びよーん", "§bびよーん", false),
    KILL("kill", "4", Material.IRON_SWORD, "<red>みちづれ", "§cみちづれ", false),
    POWER("power", "5", Material.GOLD_NUGGET, "<green>ぱわー!!", "§aぱわー!!", true),
    ;

    companion object {
        fun byArg(arg: String): SpecialItem? = entries.firstOrNull { it.id.equals(arg, true) || it.number == arg }
    }
}

/**
 * Creates and recognizes special items. Items are tagged in their PersistentDataContainer
 * (the old plugin compared display names, which anyone could forge with an anvil); items made by
 * the old plugin are still recognized by their exact legacy name.
 */
class SpecialItems(private val plugin: JunkSurvival) {
    private val key = NamespacedKey(plugin, "special_item")

    fun create(type: SpecialItem): ItemStack = ItemStack(type.material).apply {
        editMeta {
            it.displayName(plugin.mm.deserialize(type.displayName).decoration(TextDecoration.ITALIC, false))
            it.persistentDataContainer.set(key, PersistentDataType.STRING, type.id)
        }
    }

    fun typeOf(item: ItemStack?): SpecialItem? {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null
        val meta = item.itemMeta
        meta.persistentDataContainer.get(key, PersistentDataType.STRING)?.let { id -> return SpecialItem.entries.firstOrNull { it.id == id } }
        val name = meta.displayName() ?: return null
        val legacy = LegacyComponentSerializer.legacySection().serialize(name)
        return SpecialItem.entries.firstOrNull { it.legacyName == legacy && it.material == item.type }
    }
}
