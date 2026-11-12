package net.azisaba.jg.sdk

import net.azisaba.jg.sdk.event.EventEngine
import net.azisaba.jg.sdk.event.JunkGameEvent
import net.azisaba.jg.sdk.event.JunkGameListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * A game provided as a plugin (the SDK of JunkGamesCore 0.x, restored).
 *
 * Every member keeps its old Java name: `getDisplayName()`, `getFavicon()`, `getCategory()`,
 * `getLore()`, `getPriority()`, `onJunkGameCommand(Player)` … so old game plugins still compile.
 */
interface IJunkGame {
    /** The plugin name (implemented by `JavaPlugin#getName`). */
    fun getName(): String

    /** Display name (green is recommended for game titles). */
    val displayName: Component

    /** Icon in the selection screen. */
    val favicon: Material

    /** Categories, separated by spaces. */
    val category: String

    /** Order in the selection screen (lower first). */
    val priority: Int

    val stack: ItemStack
    val lore: List<Component>

    /** Players in the declared worlds. */
    val players: List<Player>
    val worlds: List<World>
    val listeners: List<JunkGameListener>
    val commands: MutableList<IJunkGameCommand>

    fun isPlayer(player: Player): Boolean

    /** Called when a player connects (selection screen, `/junkgames <game>`). Send them to the game's lobby here. */
    fun onJunkGameCommand(player: Player)

    fun call(event: JunkGameEvent)

    operator fun contains(world: World): Boolean

    fun broadcast(message: Component)
}

/**
 * Base class of a game plugin: extend this instead of `JavaPlugin`.
 *
 * ```kotlin
 * class Example : JunkGame() {
 *     override val displayName = Component.text("Example", NamedTextColor.GREEN)
 *     override val favicon = Material.DIAMOND
 *     override val category = "PvP"
 *     override val lore = listOf(Component.text("説明"))
 *     override val priority = 10
 *     override fun onJunkGameCommand(player: Player) { player.teleport(...) }
 *     override fun onEnable() { addWorld("example"); addListener(ExampleListener()) }
 * }
 * ```
 */
abstract class JunkGame : JavaPlugin(), IJunkGame {
    /** The hub (formerly the `JunkGames` plugin instance). */
    val junkGames: JunkGamesSdk? = JunkGamesSdk.getOrNull()
    protected val event: EventEngine by lazy { EventEngine(logger) }
    private val worldList = CopyOnWriteArrayList<World>()
    private val worldNames = CopyOnWriteArrayList<String>()
    override val commands: MutableList<IJunkGameCommand> = CopyOnWriteArrayList()

    init {
        // registered as soon as the plugin is constructed, like the old SDK
        junkGames?.register(this)
        lastConstructed = this
    }


    override val stack: ItemStack
        get() = ItemStack(favicon).apply {
            val game = this@JunkGame
            editMeta { meta ->
                meta.displayName(game.displayName.decoration(TextDecoration.ITALIC, false))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
                val header: List<Component> = listOf(
                    Component.text(game.category, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                )
                meta.lore(header + game.lore)
            }
        }

    override val players: List<Player> get() = Bukkit.getOnlinePlayers().filter { contains(it.world) }

    override val worlds: List<World>
        get() = (worldList + worldNames.mapNotNull { Bukkit.getWorld(it) }).distinct()

    override val listeners: List<JunkGameListener> get() = event.listeners()

    /** Declares a world used by this game (world partition: players there belong to this game). */
    fun addWorld(world: World) {
        worldList += world
    }

    /** Declares a world by name. Unlike the old SDK, worlds loaded later are matched too. */
    fun addWorld(name: String) {
        worldNames += name
    }

    /** Registers SDK listeners; if the object is also a Bukkit [Listener], it is registered to Bukkit too. */
    protected fun addListener(listener: JunkGameListener) {
        event.register(listener)
        if (listener is Listener) server.pluginManager.registerEvents(listener, this)
    }

    override fun isPlayer(player: Player): Boolean = contains(player.world)

    override fun call(event: JunkGameEvent) = this.event.call(event)

    override fun contains(world: World): Boolean = world in worldList || world.name in worldNames

    override fun broadcast(message: Component) = players.forEach { it.sendMessage(message) }

    companion object {
        @Volatile
        private var lastConstructed: JunkGame? = null

        /** Old static accessor. Ambiguous when several games are installed; prefer your own instance. */
        @Deprecated("ambiguous with several games", ReplaceWith("JavaPlugin.getPlugin(YourGame::class.java)"))
        @JvmStatic
        fun getPlugin(): JunkGame? = lastConstructed

        @Deprecated("ambiguous with several games")
        @JvmStatic
        fun getPluginLogger(): Logger? = lastConstructed?.logger
    }
}

interface IJunkGameCommand : CommandExecutor {
    fun getName(): String
    fun getProvider(): IJunkGame
    fun execute(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean
}

/**
 * A command usable only inside its game. Elsewhere it is refused and hidden from tab completion.
 * Declare the command in your plugin.yml and bind it with `getCommand(...)!!.setExecutor(...)` as usual;
 * implement [execute] instead of `onCommand`.
 */
abstract class JunkGameCommand : IJunkGameCommand {
    init {
        @Suppress("LeakingThis")
        getProvider().commands += this
    }

    @Deprecated("implement execute")
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender is Player && JunkGamesSdk.getOrNull()?.getJunkGame(sender) !== getProvider()) {
            sender.sendMessage(Component.text("ここではこのコマンドを使用することはできません", NamedTextColor.RED))
            return true
        }
        return execute(sender, command, label, args)
    }
}
