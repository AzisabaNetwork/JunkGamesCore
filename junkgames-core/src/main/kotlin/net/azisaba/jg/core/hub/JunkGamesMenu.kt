package net.azisaba.jg.core.hub

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.azisaba.jg.ui.InventoryUI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * The game selection screen (`/junkgames`), restored: main lobby, games with "click to connect" /
 * PLAYING and player counts, random game, close and report.
 */
class JunkGamesMenu(private val hub: Hub, player: Player) :
    InventoryUI(player, Bukkit.createInventory(null, 54, Component.text("Junk Games"))) {

    private var entries = hub.entries()
    private var randomStack = button(Material.STRING, "ランダムなゲーム", NamedTextColor.GREEN, "ランダムなゲームに参加")
    private var frame = 0
    private val task: ScheduledTask?

    init {
        inventory.setItem(4, button(Material.BOOKSHELF, "メインロビー", NamedTextColor.GREEN, "メインロビーに戻る"))
        inventory.setItem(48, randomStack)
        inventory.setItem(49, button(Material.OAK_DOOR, "閉じる", NamedTextColor.RED, "この画面を閉じます"))
        inventory.setItem(50, button(Material.PAPER, "通報", NamedTextColor.GREEN, "ルール違反ですか？レポートを作成しましょう…"))
        render()
        task = player.scheduler.runAtFixedRate(hub.core.plugin, { render() }, null, 10L, 10L)
    }

    private fun render() {
        if (frame % 10 == 0) entries = hub.entries()
        val blink = frame % 2 == 0
        for (slot in SLOTS) {
            inventory.setItem(slot, null)
            listeners.remove(slot)
        }
        entries.take(SLOTS.size).forEachIndexed { i, e ->
            val stack = e.stack.clone()
            val lore = ArrayList(stack.lore() ?: emptyList())
            lore += Component.empty()
            lore += if (e.isPlaying(player)) {
                Component.text("PLAYING", NamedTextColor.GREEN).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
            } else {
                Component.text((if (blink) "▶" else "  ") + " クリックして接続", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
            }
            lore += Component.text("${e.playerCount()} 人がプレイ中！", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            stack.lore(lore)
            // (the old screen put every game into the first slot)
            addListener(SLOTS[i], stack) { p ->
                p.closeInventory()
                e.connect(p)
            }
        }
        if (entries.isNotEmpty()) {
            // ItemStack#setType is deprecated; withType copies the stack (name and lore included)
            randomStack = randomStack.withType(entries[frame % entries.size].favicon)
            inventory.setItem(48, randomStack)
        }
        frame++
    }

    override fun onClick(event: InventoryClickEvent) {
        super.onClick(event)
        if (event.clickedInventory !== inventory) return
        when (event.slot) {
            4 -> {
                player.closeInventory()
                val inst = hub.core.instances.byPlayer(player.uniqueId)
                if (inst != null) hub.core.instances.leave(player) else player.teleportAsync(hub.getSpawn())
            }
            48 -> {
                val candidates = entries.filterNot { it.isPlaying(player) }
                if (candidates.isNotEmpty()) {
                    player.closeInventory()
                    candidates.random().connect(player)
                }
            }
            49 -> player.closeInventory()
        }
    }

    override fun onClose(event: InventoryCloseEvent) {
        super.onClose(event)
        task?.cancel()
    }

    private fun button(material: Material, name: String, color: NamedTextColor, lore: String) = ItemStack(material).apply {
        editMeta {
            it.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
            it.lore(listOf(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
        }
    }

    private companion object {
        val SLOTS = intArrayOf(10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
    }
}
