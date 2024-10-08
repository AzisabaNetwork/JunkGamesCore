package net.azisaba.jg.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.azisaba.jg.JunkGames;
import net.azisaba.jg.sdk.IJunkGame;
import net.azisaba.jg.sdk.IJunkGameCommand;
import net.azisaba.jg.util.Typing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;

public class PlayerListener implements Listener
{
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        player.teleport(JunkGames.spawn);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();
        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.call(new net.azisaba.jg.sdk.event.PlayerQuitEvent(player));
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
            from.call(new net.azisaba.jg.sdk.event.PlayerQuitEvent(player));
        }

        if (to != null)
        {
            to.call(new net.azisaba.jg.sdk.event.PlayerJoinEvent(player));
        }
    }

    @EventHandler
    public void onPlayerCommandSend(PlayerCommandSendEvent event)
    {
        for (IJunkGame game : JunkGames.getPlugin().getJunkGames().stream().filter(g -> g != JunkGames.getPlugin().getJunkGame(event.getPlayer())).toList())
        {
            for (IJunkGameCommand command : game.getCommands())
            {
                event.getCommands().removeIf(c -> c.equalsIgnoreCase(command.getName()) || c.equalsIgnoreCase("/" + command.getName()) || c.equalsIgnoreCase(game.getName() + ":" + command.getName()));
            }
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event)
    {
        if (! (event.getEntity() instanceof Player player))
        {
            return;
        }

        event.setCancelled(event.isCancelled() || JunkGames.lobby.getPlayers().contains(player));
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event)
    {
        event.setCancelled(true);

        Player player = event.getPlayer();
        Typing typing = Typing.getInstance(player);

        if (typing != null)
        {
            String content = ((TextComponent) event.message()).content();

            player.sendMessage(Component.text(" " + content).color(NamedTextColor.GRAY));
            typing.onTyped(content);
            return;
        }

        IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

        if (game != null)
        {
            game.call(new net.azisaba.jg.sdk.event.PlayerChatEvent(event));
        }
        else
        {
            player.getWorld().getPlayers().forEach(p -> p.sendMessage(p.displayName().append(Component.text(": ").color(NamedTextColor.GRAY)).append(event.message())));
        }
    }
}
