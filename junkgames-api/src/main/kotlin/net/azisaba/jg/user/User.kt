package net.azisaba.jg.user

import net.azisaba.jg.util.ComponentUtility
import net.azisaba.jg.util.UserUtility
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

/** Supporter / staff rank ("皿") shown before names (restored). */
enum class Sara(@JvmField val level: Int, @JvmField val role: Component, @JvmField val color: TextColor) {
    DEFAULT(0, Component.text(""), NamedTextColor.WHITE),
    `$100YEN`(1, Component.text("[100円皿] ", NamedTextColor.BLUE), NamedTextColor.BLUE),
    `$500YEN`(2, Component.text("[500円皿] ", NamedTextColor.AQUA), NamedTextColor.AQUA),
    `$1000YEN`(3, Component.text("[1000円皿] ", NamedTextColor.DARK_GREEN), NamedTextColor.DARK_GREEN),
    `$2000YEN`(4, Component.text("[2000円皿] ", NamedTextColor.LIGHT_PURPLE), NamedTextColor.LIGHT_PURPLE),
    `$5000YEN`(5, Component.text("[5000円皿] ", NamedTextColor.DARK_PURPLE), NamedTextColor.DARK_PURPLE),
    `$10000YEN`(6, Component.text("[10000円皿] ", NamedTextColor.GOLD), NamedTextColor.GOLD),
    `$50000YEN`(7, Component.text("[50000円皿] ", NamedTextColor.DARK_RED), NamedTextColor.GOLD),
    GAMING(8, ComponentUtility.getAsGaming("[Gaming] "), NamedTextColor.AQUA),
    NITRO(9, ComponentUtility.getAsGaming("[Nitro] "), NamedTextColor.LIGHT_PURPLE),
    MOD(10, Component.text("[MOD] ", NamedTextColor.YELLOW), NamedTextColor.YELLOW),
    ADMIN(11, Component.text("[ADMIN] ", NamedTextColor.RED), NamedTextColor.RED),
    SYSTEM(11, Component.text("[SYSTEM] ", NamedTextColor.GRAY), NamedTextColor.GRAY),
}

/** A server user (restored). */
interface IUser {
    fun getId(): UUID

    fun getSara(): Sara

    /** Name with the rank prefix and color. */
    fun getName(): Component = getSara().role.append(Component.text(getPlaneName() ?: "?", getSara().color))

    fun getPlaneName(): String? = getAsOfflinePlayer().name

    fun getAsPlayer(): Player? = Bukkit.getPlayer(getId())

    fun getAsOfflinePlayer(): OfflinePlayer = Bukkit.getOfflinePlayer(getId())

    fun isOnline(): Boolean = getAsOfflinePlayer().isOnline

    companion object {
        /** A user whose rank is computed from the online player's permissions. */
        @JvmStatic
        fun of(player: Player): IUser = object : IUser {
            override fun getId(): UUID = player.uniqueId
            override fun getSara(): Sara = UserUtility.calculateSara(player)
        }
    }
}
