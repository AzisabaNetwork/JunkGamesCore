package net.azisaba.jg.plugin.sample

import net.azisaba.jg.api.JunkGamesApi
import net.azisaba.jg.api.block.BlockBuilder
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.Category
import net.azisaba.jg.api.type.ValueType
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Sample JunkGames plugin.
 *
 * 1. adds blocks to the editor / engine through [JunkGamesApi]
 * 2. declares its command in plugin.yml
 * 3. ships a game (`games/sample_storm.jgame.json`) and a map that use those blocks
 */
class SamplePlugin : JavaPlugin() {
    override fun onEnable() {
        // blocks are unregistered automatically when this plugin is disabled
        val registry = JunkGamesApi.get().blocks
        SampleBlocks.all(name).forEach { registry.register(this, it) }

        val cmd = SampleCommand(this)
        getCommand("jgsample")!!.apply {
            setExecutor(cmd)
            tabCompleter = cmd
        }
        installGame(overwrite = false)
    }

    /** Copies the bundled game and map into JunkGamesCore's folders and reloads definitions. */
    fun installGame(overwrite: Boolean) {
        val coreDir = server.pluginManager.getPlugin("JunkGamesCore")!!.dataFolder.toPath()
        var copied = false
        for (resource in listOf("games/sample_storm.jgame.json", "maps/sample_plaza.jmap.json")) {
            val target = coreDir.resolve(resource)
            if (!overwrite && Files.exists(target)) continue
            Files.createDirectories(target.parent)
            getResource(resource)!!.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            copied = true
        }
        if (copied) JunkGamesApi.get().reloadDefinitions()
    }
}

/** The blocks this plugin adds. Kept apart from the plugin class so tests can compile games with them. */
object SampleBlocks {
    private val COLORS = listOf("red", "gold", "yellow", "green", "aqua", "blue", "light_purple")

    fun all(owner: String): List<BlockDefinition> = listOf(
        BlockBuilder.stack("sample.lightning")
            .owner(owner)
            .category(Category.WORLD)
            .label("%location に雷を落とす（ダメージ: %damage）", "strike lightning at %location (damage: %damage)")
            .input("location", ValueType.LOCATION)
            .dropdown("damage", "true", "true", "false")
            .exec { _, a ->
                // invalid / missing values are silent no-ops (spec §5.5)
                val loc = a.location("location") ?: return@exec
                if (a.field("damage") == "true") loc.world.strikeLightning(loc) else loc.world.strikeLightningEffect(loc)
            },

        BlockBuilder.reporter("sample.random_color", ValueType.STRING)
            .owner(owner)
            .category(Category.OPERATOR)
            .label("ランダムな色タグ", "random color tag")
            .eval { ex, _ -> "<${COLORS[ex.game.random.nextInt(COLORS.size)]}>" },
    )
}

/** `/jgsample <lightning|install>` — declared in plugin.yml. */
class SampleCommand(private val plugin: SamplePlugin) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        when (args.getOrNull(0)) {
            "lightning" -> {
                val p = sender as? Player ?: return true.also { sender.sendRichMessage("<red>プレイヤーのみ実行できます") }
                val target = p.getTargetBlockExact(50)?.location ?: p.location
                target.world.strikeLightningEffect(target)
            }
            "install" -> {
                plugin.installGame(overwrite = true)
                sender.sendRichMessage("<green>サンプルゲームを JunkGamesCore に入れ直しました。<gray>/play sample_storm")
            }
            else -> return false // shows `usage` from plugin.yml
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> =
        if (args.size == 1) listOf("lightning", "install").filter { it.startsWith(args[0]) } else emptyList()
}
