package net.azisaba.jg.plugin.junksurvival

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import net.azisaba.jg.sdk.JunkGame
import org.bukkit.Material
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * JunkSurvival: a chaotic survival — breaking blocks drops random items and special items,
 * the world border grows with items and shrinks on death, and difficulty is chosen in the lobby.
 * Ported from the Spigot 1.17 version (3.2.1). A JunkGames SDK game: it owns the survival worlds
 * and is listed in the `/junkgames` selection screen.
 */
class JunkSurvival : JunkGame() {
    override val displayName: Component = Component.text("JunkSurvival", NamedTextColor.GREEN)
    override val favicon: Material = Material.GRASS_BLOCK
    override val category: String = "サバイバル"
    override val lore: List<Component> = listOf(
        Component.text("ブロックを壊すと何かが出る、めちゃくちゃなサバイバル", NamedTextColor.GRAY),
        Component.text("ワールドボーダーを広げて生き延びよう", NamedTextColor.GRAY),
    )
    override val priority: Int = 100

    /** Connect: to the spawn of the first survival world. */
    override fun onJunkGameCommand(player: Player) {
        val world = worlds.firstOrNull() ?: return player.sendRichMessage("<red>JunkSurvival のワールドが読み込まれていません")
        player.teleportAsync(world.spawnLocation)
    }

    val mm: MiniMessage = MiniMessage.miniMessage()
    lateinit var items: SpecialItems
        private set
    lateinit var survivalWorlds: Set<String>
        private set
    lateinit var lobbyWorld: String
        private set

    /** Difficulty chosen per player (default: super easy, like the old plugin). */
    val difficulty = ConcurrentHashMap<UUID, Difficulty>()
    val welcomeBar = BossBar.bossBar(Component.text("JunkSurvivalへようこそ!", NamedTextColor.RED), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS)

    override fun onEnable() {
        saveDefaultConfig()
        survivalWorlds = config.getStringList("survival-worlds").map { it.lowercase() }.toSet()
        config.getStringList("survival-worlds").forEach { addWorld(it) } // world partition (SDK)
        lobbyWorld = config.getString("lobby-world", "world")!!
        Difficulty.entries.forEach { d -> d.divisor = config.getDouble("damage-divisor.${d.configKey}", d.divisor) }
        items = SpecialItems(this)

        server.pluginManager.registerEvents(SurvivalListener(this), this)
        server.pluginManager.registerEvents(SpecialItemListener(this), this)
        addListener(PlayerListener(this)) // SDK + Bukkit listener

        val cmd = GiveCommand(this)
        getCommand("junksurvivalgive")!!.apply {
            setExecutor(cmd)
            tabCompleter = cmd
        }

        val interval = config.getLong("buff-interval-ticks", 50000)
        server.scheduler.runTaskTimer(this, Runnable { periodicBuff() }, interval, interval)
    }

    fun isSurvivalWorld(world: World?): Boolean = world != null && world.name.lowercase() in survivalWorlds

    fun broadcast(miniMessage: String) = Bukkit.broadcast(mm.deserialize(miniMessage))

    /** The "everything at 255" buff of the old plugin. */
    fun applyBuffs(p: Player, withStrength: Boolean) {
        val types = buildList {
            add(PotionEffectType.INSTANT_HEALTH)
            add(PotionEffectType.REGENERATION)
            add(PotionEffectType.RESISTANCE)
            add(PotionEffectType.CONDUIT_POWER)
            add(PotionEffectType.SATURATION)
            if (withStrength) add(PotionEffectType.STRENGTH)
            add(PotionEffectType.WATER_BREATHING)
            add(PotionEffectType.INVISIBILITY)
        }
        types.forEach { p.addPotionEffect(PotionEffect(it, 4000, 255)) }
    }

    private fun periodicBuff() {
        for (p in Bukkit.getOnlinePlayers()) {
            if (!isSurvivalWorld(p.world)) continue
            p.sendMessage(mm.deserialize("<green><bold>junkSurvival</bold></green>対象ワールドにいる\n<green><bold>全プレイヤーに一定時間のバフが付与されたよ！"))
            applyBuffs(p, withStrength = true)
            p.world.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        }
    }
}

enum class Difficulty(val configKey: String, var divisor: Double, val block: org.bukkit.Material, val message: String) {
    SUPER_EASY("super_easy", 95.0, org.bukkit.Material.EMERALD_BLOCK, "<green>すーぱーいーじーもーどおおおおお！！！！！"),
    EASY("easy", 80.0, org.bukkit.Material.DIAMOND_BLOCK, "<aqua>いーじーもーどおおおおお！！！！！"),
    NORMAL("normal", 75.0, org.bukkit.Material.IRON_BLOCK, "<bold>のーまるもーど"),
    HARD("hard", 50.0, org.bukkit.Material.REDSTONE_BLOCK, "<red>きついかもよ？"),
}
