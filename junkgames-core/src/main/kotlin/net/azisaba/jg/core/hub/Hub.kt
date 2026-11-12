package net.azisaba.jg.core.hub

import net.azisaba.jg.JunkGames
import net.azisaba.jg.api.runtime.Values
import net.azisaba.jg.core.Core
import net.azisaba.jg.core.provider.paper.VoidGenerator
import net.azisaba.jg.sdk.IJunkGame
import net.azisaba.jg.sdk.JunkGamesSdk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The hub of JunkGames (restored from JunkGamesCore 0.x): lobby world and spawn, registry of SDK
 * games (plugins extending `JunkGame`) with world partitioning, and the game selection screen,
 * which lists SDK games and block-made games together.
 */
class Hub(private val plugin: Plugin) : JunkGamesSdk {
    lateinit var core: Core
    private val games = CopyOnWriteArrayList<IJunkGame>()
    private lateinit var lobbyWorld: World
    private lateinit var spawn: Location

    // ---------------------------------------------------------------- lobby

    /** Loads (or creates) the lobby world and spawn. Main thread, on enable. */
    fun setupLobby() {
        val c = plugin.config
        val name = c.getString("lobby.world").takeUnless { it.isNullOrBlank() } ?: "lobby"
        var created = false
        val world = Bukkit.getWorld(name) ?: if (c.getBoolean("lobby.create-if-missing", true)) {
            created = true
            WorldCreator(name).generator(VoidGenerator()).createWorld()?.also { w ->
                w.setGameRule(GameRules.ADVANCE_TIME, false)
                w.setGameRule(GameRules.ADVANCE_WEATHER, false)
                w.time = 6000
            }
        } else null
        lobbyWorld = world ?: Bukkit.getWorlds().first().also { plugin.logger.warning("ロビーワールド $name がないため ${it.name} を使います") }
        // `spawn:` is the key of the old config.yml; `lobby:` the new one
        val s = c.getConfigurationSection("lobby")?.takeIf { it.contains("x") } ?: c.getConfigurationSection("spawn")
        spawn = if (s != null) {
            Location(lobbyWorld, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), s.getDouble("yaw").toFloat(), s.getDouble("pitch").toFloat())
        } else lobbyWorld.spawnLocation
        if (created) {
            // a fresh void lobby: give the spawn something to stand on
            val b = spawn.block.getRelative(0, -1, 0)
            for (dx in -3..3) for (dz in -3..3) {
                val t = b.getRelative(dx, 0, dz)
                if (t.type.isAir) t.type = Material.SMOOTH_STONE
            }
        }
        JunkGames.lobby = lobbyWorld
        JunkGames.spawn = spawn
    }

    override fun getLobby(): World = lobbyWorld

    override fun getSpawn(): Location = spawn.clone()

    // ---------------------------------------------------------------- SDK games

    /** The connect commands registered for each game (`/<game>` and `/junkgames:<game>`). */
    private val connectCommands = ConcurrentHashMap<IJunkGame, JunkGameConnectCommand>()

    /**
     * Registers a game and, as in 0.x, its connect command `/<game>` (also `/junkgames:<game>`).
     * Called from `JunkGame`'s constructor, i.e. while plugins are loading.
     */
    override fun register(game: IJunkGame) {
        if (games.any { it === game }) return
        games += game
        val command = JunkGameConnectCommand(game, this)
        connectCommands[game] = command
        Bukkit.getCommandMap().register(game.getName(), COMMAND_PREFIX, command)
    }

    override fun unregister(game: IJunkGame) {
        games.removeIf { it === game }
        val command = connectCommands.remove(game) ?: return
        val map = Bukkit.getCommandMap()
        command.unregister(map)
        map.knownCommands.entries.removeIf { it.value === command }
    }

    override fun getJunkGame(world: World): IJunkGame? = games.firstOrNull { it.contains(world) }

    override fun getJunkGame(player: Player): IJunkGame? = getJunkGame(player.world)

    /** Sorted when read: Kotlin subclasses' `priority` is not initialized yet while registering. */
    override fun getJunkGames(): List<IJunkGame> = games.sortedBy { it.priority }

    override fun openMenu(player: Player) {
        JunkGamesMenu(this, player)
    }

    override fun connect(player: Player, game: IJunkGame) {
        if (game.isPlayer(player)) {
            player.sendMessage(Component.text("あなたは既に ${game.getName()} に接続しています", NamedTextColor.RED))
            return
        }
        game.onJunkGameCommand(player)
    }

    // ---------------------------------------------------------------- selection screen entries

    /** One game in the selection screen, either an SDK game or a block-made game. */
    class Entry(
        val id: String,
        val priority: Int,
        val stack: ItemStack,
        val favicon: Material,
        val isPlaying: (Player) -> Boolean,
        val playerCount: () -> Int,
        val connect: (Player) -> Unit,
    )

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        for (g in games) {
            if ((g as? Plugin)?.isEnabled == false) continue
            // like 0.x, the screen runs the game's connect command (so other plugins can hook it)
            out += Entry(g.getName(), g.priority, g.stack, g.favicon, { g.isPlayer(it) }, { g.players.size }) { p ->
                p.performCommand("$COMMAND_PREFIX:${g.getName().lowercase()}")
            }
        }
        for (lg in core.definitions.games()) {
            val c = lg.compiled ?: continue
            if (!lg.playable || core.definitions.compatibleMaps(c).isEmpty()) continue
            val m = c.definition.meta
            val favicon = Values.parseMaterial(m.favicon)?.takeIf { it.isItem } ?: Material.PAPER
            val mm = MiniMessage.miniMessage()
            val stack = ItemStack(favicon).apply {
                editMeta { meta ->
                    meta.displayName(Component.text(m.displayName, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
                    val lines = ArrayList<Component>()
                    lines += Component.text(m.category.ifBlank { "ミニゲーム" }, NamedTextColor.DARK_GRAY)
                    lines += Component.empty()
                    if (m.description.isNotBlank()) lines += Component.text(m.description, NamedTextColor.GRAY)
                    m.lore.forEach { lines += mm.deserialize(it) }
                    lines += Component.text("${m.minPlayers}〜${m.maxPlayers}人", NamedTextColor.GRAY)
                    meta.lore(lines.map { it.decoration(TextDecoration.ITALIC, false) })
                }
            }
            out += Entry(
                c.id, m.priority, stack, favicon,
                { p -> core.instances.byPlayer(p.uniqueId)?.compiled?.id == c.id },
                { core.instances.all().filter { it.compiled.id == c.id }.sumOf { it.players().size } },
                { p -> core.instances.join(p, c.id, null) },
            )
        }
        return out.sortedBy { it.priority }
    }

    companion object {
        /** Namespace of the connect commands, kept from 0.x (whose plugin was named JunkGames). */
        const val COMMAND_PREFIX = "junkgames"
    }
}

/**
 * `/<game>` / `/junkgames:<game>`: connects to an SDK game (0.x behaviour kept). This is the one
 * kind of command not declared in plugin.yml, because it exists once per installed game.
 */
class JunkGameConnectCommand(private val game: IJunkGame, private val hub: Hub) : Command(game.getName()) {
    init {
        description = "${game.getName()} に接続する"
        usage = "/${game.getName()}"
    }

    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Please run this from within the game.", NamedTextColor.RED))
            return true
        }
        if (args.isNotEmpty()) {
            sender.sendMessage(Component.text("Correct syntax: /${game.getName()}", NamedTextColor.RED))
            return true
        }
        hub.connect(player, game) // "already connected" check included
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> = emptyList()
}
