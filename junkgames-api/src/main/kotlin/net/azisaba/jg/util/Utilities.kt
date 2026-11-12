package net.azisaba.jg.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.azisaba.jg.user.IUser
import net.azisaba.jg.user.Sara
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Restored utilities of JunkGamesCore 0.x. All members are static for Java callers. */
object ComponentUtility {
    private val GAMING = arrayOf(
        NamedTextColor.RED, NamedTextColor.YELLOW, NamedTextColor.GREEN, NamedTextColor.AQUA,
        NamedTextColor.BLUE, NamedTextColor.DARK_PURPLE, NamedTextColor.LIGHT_PURPLE,
    )

    /** Rainbow text, one color per character. */
    @JvmStatic
    fun getAsGaming(src: String): Component =
        src.foldIndexed(Component.text("")) { i, acc, c -> acc.append(Component.text(c, GAMING[i % GAMING.size])) }
}

object MathUtility {
    @JvmStatic
    fun isInt(s: String): Boolean = s.matches(Regex("^[0-9]+$"))

    @JvmStatic
    fun isDouble(s: String): Boolean = s.toDoubleOrNull() != null
}

object UuidUtility {
    @JvmStatic
    fun isUuid(s: String): Boolean = runCatching { UUID.fromString(s) }.isSuccess
}

object PlayerUtility {
    /** Gives items; what does not fit is dropped at the player's feet. */
    @JvmStatic
    fun giveItemStack(player: Player, stacks: List<ItemStack?>) {
        val remaining = stacks.filterNotNull().filter { it.amount > 0 }.flatMap { player.inventory.addItem(it).values }
        remaining.forEach { player.world.dropItem(player.location, it) }
    }

    @JvmStatic
    fun giveItemStack(player: Player, stack: ItemStack) = giveItemStack(player, listOf(stack))
}

/** Files in a plugin's data folder. (Any plugin is accepted; the old version took `JunkGame`.) */
object ResourceUtility {
    @JvmStatic
    fun getResource(game: Plugin, name: String): File = File(game.dataFolder, name)

    @JvmStatic
    fun getYamlResource(game: Plugin, name: String): YamlConfiguration = YamlConfiguration.loadConfiguration(getResource(game, name))

    @JvmStatic
    fun getJsonResource(game: Plugin, name: String): JsonElement = getResource(game, name).reader().use { JsonParser.parseReader(it) }

    @JvmStatic
    fun save(game: Plugin, name: String, resource: YamlConfiguration) = resource.save(getResource(game, name))

    @JvmStatic
    fun save(game: Plugin, name: String, resource: JsonElement) {
        val f = getResource(game, name)
        f.parentFile.mkdirs()
        f.writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(resource))
    }

    /** (The old version checked a wrong path and always returned false.) */
    @JvmStatic
    fun exists(game: Plugin, name: String): Boolean = Files.exists(getResource(game, name).toPath())
}

object SqlUtility {
    /** Tries to connect and run `SHOW DATABASES`. Blocking: never call from a tick thread. */
    @JvmStatic
    fun test(url: String, user: String, pass: String): Boolean = try {
        DriverManager.getConnection(url, user, pass).use { con -> con.prepareStatement("SHOW DATABASES").use { it.execute() } }
        true
    } catch (e: SQLException) {
        false
    }

    @JvmStatic
    fun jdbc(driver: String) {
        runCatching { Class.forName(driver) }
    }
}

object UserUtility {
    private val ranks = listOf(
        "group.nitro" to Sara.NITRO,
        "group.gamingsara" to Sara.GAMING,
        "group.50000yen" to Sara.`$50000YEN`,
        "group.10000yen" to Sara.`$10000YEN`,
        "group.5000yen" to Sara.`$5000YEN`,
        "group.2000yen" to Sara.`$2000YEN`,
        "group.1000yen" to Sara.`$1000YEN`,
        "group.500yen" to Sara.`$500YEN`,
        "group.100yen" to Sara.`$100YEN`,
    )

    @JvmStatic
    fun calculateSara(player: Player): Sara {
        if (player.isOp) return Sara.ADMIN
        return ranks.firstOrNull { player.hasPermission(it.first) }?.second ?: Sara.DEFAULT
    }

    @JvmStatic
    fun isModerator(user: IUser): Boolean = user.getSara().level >= 10

    @JvmStatic
    fun isAdmin(user: IUser): Boolean = user.getSara().level >= 11
}

/**
 * Captures the player's next chat message (restored). Subclass it and override [onTyped]
 * (call `super.onTyped` to stop listening). The message is not broadcast. [onTyped] runs on the
 * player's thread (the old version ran it on the async chat thread).
 */
abstract class Typing(@JvmField protected val player: Player) {
    init {
        player.closeInventory()
        @Suppress("LeakingThis")
        init()
        instances[player.uniqueId] = this
    }

    open fun init() {}

    fun getPlayer(): Player = player

    /** Random confirmation code without confusable characters. */
    fun getConfirmCode(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnoprstuvwxyz12345678"
        return (1..maxOf(1, length)).map { chars.random() }.joinToString("")
    }

    open fun onTyped(string: String) {
        instances.remove(player.uniqueId, this)
    }

    companion object {
        private val instances = ConcurrentHashMap<UUID, Typing>()

        @JvmStatic
        fun getInstance(player: Player): Typing? = instances[player.uniqueId]

        @JvmStatic
        fun cancel(player: Player) {
            instances.remove(player.uniqueId)
        }
    }
}
