package net.azisaba.jg.core.command

import org.bukkit.command.Command
import org.bukkit.command.TabExecutor
import net.azisaba.jg.api.ir.Diagnostic
import net.azisaba.jg.core.JunkGamesCorePlugin
import net.azisaba.jg.core.block.BlockExporter
import net.azisaba.jg.core.map.MapEditor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.Files

private val MM = MiniMessage.miniMessage()
private fun CommandSender.msg(m: String) = sendMessage(MM.deserialize("<dark_aqua>[JG]</dark_aqua> $m"))
private fun esc(s: String) = MM.escapeTags(s)

/** `/play <game> [map]` (spec §8.2) */
class PlayCommand(private val core: JunkGamesCorePlugin) : SimpleCommand() {
    override fun execute(sender: CommandSender, args: Array<String>) {
        val p = sender as? Player ?: return sender.msg("<red>プレイヤーのみ実行できます")
        if (args.isEmpty()) {
            p.msg("<gold>遊べるゲーム:")
            core.definitions.games().filter { it.playable }.forEach { g ->
                val meta = g.compiled!!.definition.meta
                p.msg(" <yellow>${g.id}</yellow> <white>${esc(meta.displayName)}</white> <gray>${meta.minPlayers}〜${meta.maxPlayers}人 — ${esc(meta.description)}")
            }
            return
        }
        core.instances.join(p, args[0], args.getOrNull(1))
    }

    override fun suggest(sender: CommandSender, args: Array<String>): List<String> = when (args.size) {
        0, 1 -> core.definitions.games().filter { it.playable }.map { it.id }.filter { it.startsWith(args.getOrElse(0) { "" }) }
        2 -> core.definitions.game(args[0])?.compiled?.let { g -> core.definitions.compatibleMaps(g).map { it.id } }.orEmpty()
        else -> emptyList()
    }

}

/** `/leave` */
class LeaveCommand(private val core: JunkGamesCorePlugin) : SimpleCommand() {
    override fun execute(sender: CommandSender, args: Array<String>) {
        val p = sender as? Player ?: return
        core.instances.leave(p)
    }

}

/** `/jgadmin`: administration. */
class AdminCommand(private val core: JunkGamesCorePlugin) : SimpleCommand() {
    private val subs = listOf("list", "info", "instances", "start", "stop", "debug", "reload", "validate", "export-blocks", "providers", "errors")

    override fun execute(sender: CommandSender, args: Array<String>) {
        val s = sender
        when (args.getOrNull(0)) {
            "list" -> {
                s.msg("<gold>ゲーム定義:")
                core.definitions.games().forEach { g ->
                    val state = if (g.playable) "<green>OK" else "<red>無効 (${esc(g.disabledReason ?: "")})"
                    val v = g.compiled?.version?.let { "v$it" } ?: "-"
                    s.msg(" <yellow>${g.id}</yellow> <gray>$v</gray> $state <gray>${g.file.fileName}")
                }
                s.msg("<gold>マップ:")
                core.definitions.maps().forEach { m ->
                    s.msg(" <aqua>${m.id}</aqua> ${if (m.map != null) "<gray>${m.map.compatibleGames}" else "<red>エラー"}")
                }
            }
            "info", "validate" -> {
                val id = args.getOrNull(1) ?: return s.msg("<red>/jgadmin ${args[0]} <game>")
                if (args[0] == "validate") {
                    // re-read the file without touching what is loaded
                    val g = core.definitions.game(id) ?: return s.msg("<red>$id は見つかりません")
                    core.outbox.submit {
                        val r = core.definitions.loadGame(Files.readString(g.file), g.file)
                        report(s, r.id, r.diagnostics, r.disabledReason)
                    }
                    return
                }
                val g = core.definitions.game(id) ?: return s.msg("<red>$id は見つかりません")
                g.compiled?.let { c ->
                    val m = c.definition.meta
                    s.msg("<gold>${esc(m.displayName)}</gold> <gray>v${c.version} スクリプト ${c.scripts.size} / 自作ブロック ${c.customBlocks.size} / 変数 ${c.variables.size}")
                    s.msg("<gray>必要な能力: ${c.requiredCapabilities.ifEmpty { setOf("なし") }}  使用アドオン: ${c.usedBlockOwners}")
                    s.msg("<gray>遊べるマップ: ${core.definitions.compatibleMaps(c).map { it.id }}")
                }
                report(s, g.id, g.diagnostics, g.disabledReason)
            }
            "instances" -> {
                if (core.instances.all().isEmpty()) return s.msg("<gray>開催中の試合はありません")
                core.instances.all().forEach { i ->
                    val rt = i.runtime
                    s.msg(" <yellow>${i.id}</yellow> <white>${i.state}</white> <gray>map=${i.map.id} 人数=${i.players().size} runners=${rt.runnerCount} steps/tick=${rt.stepsLastTick} errors=${rt.errorCount}")
                }
            }
            "start", "stop", "debug" -> {
                val i = args.getOrNull(1)?.let { core.instances.get(it) } ?: return s.msg("<red>/jgadmin ${args[0]} <instance>")
                when (args[0]) {
                    "start" -> i.forceStart().also { s.msg("<green>${i.id} を開始します") }
                    "stop" -> i.forceStop().also { s.msg("<yellow>${i.id} を終了します") }
                    else -> {
                        val p = s as? Player ?: return
                        s.msg(if (i.toggleDebug(p.uniqueId)) "<green>${i.id} のエラー表示をオンにしました" else "<gray>${i.id} のエラー表示をオフにしました")
                    }
                }
            }
            "reload" -> {
                core.reloadConfigs()
                core.reloadDefinitions { s.msg("<green>定義を再読み込みしました（${core.definitions.games().count { it.playable }} ゲームが有効）") }
            }
            "export-blocks" -> {
                val out = core.dataFolder.toPath().resolve("export/blocks.json")
                val json = BlockExporter.export(core.settings.serverId, core.pluginMeta.version, core.registry.all(), core.capabilities)
                core.outbox.submit {
                    Files.createDirectories(out.parent)
                    Files.writeString(out, json)
                    s.msg("<green>${core.registry.all().size} 個のブロック定義を書き出しました: <gray>$out")
                }
            }
            "providers" -> {
                core.capabilities.selected().forEach { (cap, p) -> s.msg(" <yellow>$cap</yellow>: <white>${p.name}</white> <gray>${p.threading} ${p.features}") }
                core.capabilities.rejected.filterValues { it.isNotEmpty() }.forEach { (cap, r) -> s.msg(" <gray>$cap 不採用: $r") }
            }
            "errors" -> core.outbox.recent(15).ifEmpty { listOf("（なし）") }.forEach { s.msg("<gray>${esc(it)}") }
            else -> s.msg("<gray>/jgadmin ${subs.joinToString(" | ")}")
        }
    }

    private fun report(s: CommandSender, id: String, diags: List<Diagnostic>, disabled: String?) {
        if (diags.isEmpty() && disabled == null) return s.msg("<green>$id: 問題はありません")
        disabled?.let { s.msg("<red>$id: ${esc(it)}") }
        diags.take(20).forEach { d -> s.msg("${if (d.isError) "<red>" else "<yellow>"}${esc(d.toString())}") }
        if (diags.size > 20) s.msg("<gray>…ほか ${diags.size - 20} 件")
    }

    override fun suggest(sender: CommandSender, args: Array<String>): List<String> = when {
        args.size <= 1 -> subs.filter { it.startsWith(args.getOrElse(0) { "" }) }
        args[0] in setOf("info", "validate") -> core.definitions.games().map { it.id }
        args[0] in setOf("start", "stop", "debug") -> core.instances.all().map { it.id }
        else -> emptyList()
    }

}

/** `/jgmap` (spec §11). */
class MapCommand(private val editor: MapEditor) : SimpleCommand() {
    override fun execute(sender: CommandSender, args: Array<String>) {
        val p = sender as? Player ?: return sender.msg("<red>プレイヤーのみ実行できます")
        val name = args.getOrNull(2)
        when (args.getOrNull(0)) {
            "edit" -> args.getOrNull(1)?.let { editor.edit(p, it) } ?: p.msg("<red>/jgmap edit <マップID>")
            "point" -> when (args.getOrNull(1)) {
                "add" -> name?.let { editor.addPoint(p, it) } ?: p.msg("<red>/jgmap point add <名前>")
                "clear" -> name?.let { editor.clearPoint(p, it) } ?: p.msg("<red>/jgmap point clear <名前>")
                else -> p.msg("<red>/jgmap point <add|clear> <名前>")
            }
            "region" -> when (args.getOrNull(1)) {
                "add" -> name?.let { editor.addRegion(p, it) } ?: p.msg("<red>/jgmap region add <名前>")
                "clear" -> name?.let { editor.clearRegion(p, it) } ?: p.msg("<red>/jgmap region clear <名前>")
                else -> p.msg("<red>/jgmap region <add|clear> <名前>")
            }
            "game" -> args.getOrNull(2)?.let { editor.addGame(p, it) } ?: p.msg("<red>/jgmap game add <ゲームID>")
            "wand" -> p.inventory.addItem(editor.wand())
            "info" -> editor.info(p)
            "save" -> editor.save(p)
            "exit" -> editor.exit(p)
            else -> p.msg("<gray>/jgmap edit | point | region | game | wand | info | save | exit")
        }
    }

    override fun suggest(sender: CommandSender, args: Array<String>): List<String> = when (args.size) {
        0, 1 -> listOf("edit", "point", "region", "game", "wand", "info", "save", "exit").filter { it.startsWith(args.getOrElse(0) { "" }) }
        2 -> when (args[0]) {
            "point", "region" -> listOf("add", "clear")
            "game" -> listOf("add")
            else -> emptyList()
        }
        else -> emptyList()
    }

}

@Suppress("unused")
private fun online() = Bukkit.getOnlinePlayers()

/**
 * Base for commands declared in plugin.yml. Permissions are declared there too;
 * suggestions are filtered by what has been typed of the last argument.
 */
abstract class SimpleCommand : TabExecutor {
    abstract fun execute(sender: CommandSender, args: Array<String>)

    open fun suggest(sender: CommandSender, args: Array<String>): List<String> = emptyList()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        execute(sender, args)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        val last = args.lastOrNull().orEmpty()
        return suggest(sender, args).filter { it.startsWith(last, ignoreCase = true) }
    }
}

/**
 * `/junkgames [game] [map]` (`/jg`): without arguments opens the game selection screen (as in
 * 0.x); with a name connects to an SDK game or a block-made game. The 0.x per-game commands
 * (`/<game>`, `/junkgames:<game>`) are still registered by [net.azisaba.jg.core.hub.Hub].
 */
class JunkGamesCommand(private val core: JunkGamesCorePlugin) : SimpleCommand() {
    override fun execute(sender: CommandSender, args: Array<String>) {
        val p = sender as? Player ?: return sender.msg("<red>Please run this from within the game.")
        if (args.isEmpty()) return core.hub.openMenu(p)
        val name = args[0]
        val sdkGame = core.hub.getJunkGames().firstOrNull { it.getName().equals(name, true) }
        when {
            sdkGame != null -> core.hub.connect(p, sdkGame)
            core.definitions.game(name) != null -> core.instances.join(p, name, args.getOrNull(1))
            else -> p.msg("<red>ゲーム ${esc(name)} は見つかりません")
        }
    }

    override fun suggest(sender: CommandSender, args: Array<String>): List<String> = when (args.size) {
        1 -> core.hub.getJunkGames().map { it.getName() } + core.definitions.games().filter { it.playable }.map { it.id }
        2 -> core.definitions.game(args[0])?.compiled?.let { g -> core.definitions.compatibleMaps(g).map { it.id } }.orEmpty()
        else -> emptyList()
    }
}
