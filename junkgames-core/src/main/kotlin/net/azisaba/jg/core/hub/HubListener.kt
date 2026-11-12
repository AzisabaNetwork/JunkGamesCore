package net.azisaba.jg.core.hub

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.azisaba.jg.core.Core
import net.azisaba.jg.sdk.IJunkGame
import net.azisaba.jg.sdk.event.PlayerChatEvent
import net.azisaba.jg.sdk.event.PlayerJoinEvent
import net.azisaba.jg.sdk.event.PlayerQuitEvent
import net.azisaba.jg.ui.InventoryUI
import net.azisaba.jg.util.Typing
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.player.PlayerQuitEvent as BukkitQuitEvent
import org.bukkit.event.player.PlayerJoinEvent as BukkitJoinEvent
import org.bukkit.event.server.PluginDisableEvent

/**
 * Hub behaviour restored from JunkGamesCore 0.x: lobby on join, SDK join/quit/chat events,
 * world-scoped chat, game-only commands hidden elsewhere, no hunger in the lobby, chat input
 * ([Typing]) and [InventoryUI] routing.
 */
class HubListener(private val core: Core, private val hub: Hub) : Listener {

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(e: BukkitJoinEvent) {
        val p = e.player
        // a kept match or a saved inventory takes precedence (they move the player themselves)
        if (core.instances.onJoin(p)) return
        if (core.plugin.config.getBoolean("teleport-to-lobby-on-join", true)) {
            p.teleportAsync(hub.getSpawn())
        } else {
            hub.getJunkGame(p.world)?.call(PlayerJoinEvent(p))
        }
    }

    @EventHandler
    fun onQuit(e: BukkitQuitEvent) {
        Typing.cancel(e.player)
        core.instances.onQuit(e.player)
        hub.getJunkGame(e.player.world)?.call(PlayerQuitEvent(e.player))
    }

    @EventHandler
    fun onChangedWorld(e: PlayerChangedWorldEvent) {
        val from = hub.getJunkGame(e.from)
        val to = hub.getJunkGame(e.player.world)
        if (from === to) return // moving between worlds of the same game
        from?.call(PlayerQuitEvent(e.player))
        to?.call(PlayerJoinEvent(e.player))
    }

    /** Game-only commands of other games are hidden from tab completion. */
    @EventHandler
    fun onCommandSend(e: PlayerCommandSendEvent) {
        val current = hub.getJunkGame(e.player)
        for (g in hub.getJunkGames()) {
            if (g === current) continue
            for (c in g.commands) {
                val n = c.getName()
                e.commands.removeIf { it.equals(n, true) || it.equals("/$n", true) || it.equals("${g.getName()}:$n", true) }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFood(e: FoodLevelChangeEvent) {
        val p = e.entity as? Player ?: return
        if (p.world == hub.getLobby()) e.isCancelled = true
    }

    /**
     * Chat routing: typed input → [Typing]; block-made match → that match only; SDK game → that
     * game's players (+ SDK [PlayerChatEvent]); elsewhere → the same world, `name: message`.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(e: AsyncChatEvent) {
        val p = e.player
        Typing.getInstance(p)?.let { typing ->
            e.isCancelled = true
            val content = PlainTextComponentSerializer.plainText().serialize(e.message())
            p.sendMessage(Component.text(" $content", NamedTextColor.GRAY))
            p.scheduler.run(core.plugin, { typing.onTyped(content) }, null)
            return
        }
        val inst = core.instances.byPlayer(p.uniqueId)
        if (inst != null) {
            e.viewers().removeIf { it is Player && core.instances.byPlayer(it.uniqueId) !== inst }
            return
        }
        val game: IJunkGame? = hub.getJunkGame(p.world)
        if (game != null) {
            e.viewers().removeIf { it is Player && !game.contains(it.world) }
            game.call(PlayerChatEvent(e))
            return
        }
        val world = p.world
        e.viewers().removeIf { it is Player && (it.world != world || core.instances.byPlayer(it.uniqueId) != null) }
        // (the old version showed each viewer's own name instead of the sender's)
        e.renderer(ChatRenderer { _, name, message, _ ->
            name.append(Component.text(": ", NamedTextColor.GRAY)).append(message)
        })
    }

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        InventoryUI.getInstance(p)?.onClick(e)
    }

    @EventHandler
    fun onInventoryClose(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        InventoryUI.getInstance(p)?.takeIf { it.getInventory() === e.inventory }?.onClose(e)
    }

    @EventHandler
    fun onPluginDisable(e: PluginDisableEvent) {
        (e.plugin as? IJunkGame)?.let { hub.unregister(it) }
    }
}
