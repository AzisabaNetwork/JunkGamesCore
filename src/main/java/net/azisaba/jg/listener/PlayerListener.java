package net.azisaba.jg.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.azisaba.jg.JunkGames;
import net.azisaba.jg.sdk.IJunkGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener
{
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();
        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.getListeners().forEach(l -> l.onPlayerJoin(player));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();
        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.getListeners().forEach(l -> l.onPlayerQuit(player));
        }
    }

    @EventHandler
    public void onPlayerDamage(PlayerDeathEvent event)
    {
        Player player = event.getPlayer();
        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.getListeners().forEach(l -> l.onPlayerDeath(player));
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event)
    {
        Player player = event.getPlayer();

        IJunkGame from = JunkGames.getPlugin().getJunkGame(event.getFrom());
        IJunkGame to = JunkGames.getPlugin().getJunkGame(event.getPlayer());

        if (from != null)
        {
            from.getListeners().forEach(l -> l.onPlayerQuit(player));
        }

        if (to != null)
        {
            to.getListeners().forEach(l -> l.onPlayerJoin(player));
        }
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event)
    {
        event.setCancelled(true);

        Player player = event.getPlayer();
        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.getListeners().forEach(l -> l.onChat(player, event.message()));
        }
        else
        {
            player.getWorld().getPlayers().forEach(p -> p.sendMessage(p.displayName().append(Component.text(": ").color(NamedTextColor.GRAY)).append(event.message())));
        }
    }
}
