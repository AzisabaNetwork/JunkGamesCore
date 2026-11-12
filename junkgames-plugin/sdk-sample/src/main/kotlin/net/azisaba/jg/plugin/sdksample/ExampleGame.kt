package net.azisaba.jg.plugin.sdksample

import net.azisaba.jg.sdk.IJunkGame
import net.azisaba.jg.sdk.JunkGame
import net.azisaba.jg.sdk.JunkGameCommand
import net.azisaba.jg.sdk.event.JunkGameHandler
import net.azisaba.jg.sdk.event.JunkGameListener
import net.azisaba.jg.sdk.event.PlayerChatEvent
import net.azisaba.jg.sdk.event.PlayerJoinEvent
import net.azisaba.jg.sdk.event.PlayerQuitEvent
import net.azisaba.jg.ui.InventoryUI
import net.azisaba.jg.user.IUser
import net.azisaba.jg.util.Typing
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * A game plugin built on the JunkGames SDK: extend [JunkGame] instead of JavaPlugin.
 * It shows up in `/junkgames`, and players in its world belong to it.
 */
class ExampleGame : JunkGame() {
    override val displayName: Component = Component.text("Example Game", NamedTextColor.GREEN)
    override val favicon: Material = Material.DIAMOND
    override val category: String = "サンプル"
    override val lore: List<Component> = listOf(Component.text("JunkGames SDK のサンプルです", NamedTextColor.GRAY))
    override val priority: Int = 1000

    private lateinit var world: World

    override fun onEnable() {
        world = Bukkit.getWorld(WORLD) ?: WorldCreator(WORLD).createWorld()!!
        addWorld(world)                  // world partition: players in this world belong to this game
        addListener(ExampleListener(this))

        // the command is declared in plugin.yml; JunkGameCommand limits it to this game
        getCommand("examplemenu")!!.setExecutor(ExampleMenuCommand(this))
    }

    /** "接続": called from the selection screen or `/junkgames JunkGamesSdkSample`. */
    override fun onJunkGameCommand(player: Player) {
        player.teleportAsync(world.spawnLocation)
    }

    val spawn: Location get() = world.spawnLocation

    private companion object {
        const val WORLD = "example_game"
    }
}

/** SDK events: same names as Bukkit's, in `net.azisaba.jg.sdk.event`. */
class ExampleListener(private val game: ExampleGame) : JunkGameListener {
    @JunkGameHandler
    fun onJoin(e: PlayerJoinEvent) {
        val user = IUser.of(e.player)
        game.broadcast(user.getName().append(Component.text(" が参加しました", NamedTextColor.GRAY)))
        e.player.sendRichMessage("<gray>/examplemenu でメニューを開けます（このゲームの中でだけ使えます）")
    }

    @JunkGameHandler
    fun onQuit(e: PlayerQuitEvent) {
        game.broadcast(Component.text("${e.player.name} が退出しました", NamedTextColor.GRAY))
    }

    /** Called asynchronously; by default the message reaches this game's players only. */
    @JunkGameHandler
    fun onChat(e: PlayerChatEvent) {
        if (e.planeMessage.contains("ping")) e.sender.sendRichMessage("<aqua>pong")
    }
}

class ExampleMenuCommand(private val game: ExampleGame) : JunkGameCommand() {
    override fun getName() = "examplemenu"

    override fun getProvider(): IJunkGame = game

    override fun execute(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: return true
        ExampleMenu(game, player)
        return true
    }
}

/** InventoryUI: clicks are cancelled; listeners run a command or a callback. */
class ExampleMenu(game: ExampleGame, player: Player) : InventoryUI(player, Bukkit.createInventory(null, 27, Component.text("Example"))) {
    init {
        addListener(11, item(Material.ENDER_PEARL, "スポーンに戻る")) { it.teleportAsync(game.spawn) }
        addListener(15, item(Material.NAME_TAG, "名前を入力する")) { p -> NameTyping(p) }
        addListener(22, item(Material.OAK_DOOR, "ゲームの選択画面"), "junkgames")
    }

    private fun item(m: Material, name: String) = ItemStack(m).apply {
        editMeta { it.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
    }
}

/** Typing: the next chat message is captured instead of being sent. */
class NameTyping(player: Player) : Typing(player) {
    private val code = getConfirmCode(4)

    override fun init() {
        player.sendRichMessage("<yellow>名前をチャットで入力してください（確認コード: $code）")
    }

    override fun onTyped(string: String) {
        super.onTyped(string)
        player.sendRichMessage("<green>「${string.replace("<", "")}」を受け付けました")
    }
}
