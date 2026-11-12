package net.azisaba.jg.plugin.junksurvival

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

/** `/junksurvivalgive get <border|bomb|jump|kill|power|1-5>` — declared in plugin.yml. */
class GiveCommand(private val plugin: JunkSurvival) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.getOrNull(0)?.equals("get", true) != true) return false
        val type = args.getOrNull(1)?.let { SpecialItem.byArg(it) } ?: return false
        val player = sender as? Player ?: return true.also { sender.sendRichMessage("<red>プレイヤーのみ実行できます") }
        player.inventory.addItem(plugin.items.create(type))
        player.sendMessage(plugin.mm.deserialize(if (type.good) "<green><bold>すげーアイテムが付与されました" else "<red><bold>やべーアイテムが付与されました"))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> = when (args.size) {
        1 -> listOf("get")
        2 -> SpecialItem.entries.map { it.id }
        else -> emptyList()
    }.filter { it.startsWith(args.last(), true) }
}
