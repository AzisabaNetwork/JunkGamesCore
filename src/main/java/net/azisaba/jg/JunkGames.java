package net.azisaba.jg;

import net.azisaba.jg.command.JunkGameCommand;
import net.azisaba.jg.command.JunkGamesCommand;
import net.azisaba.jg.listener.InventoryListener;
import net.azisaba.jg.listener.PlayerListener;
import net.azisaba.jg.sdk.IJunkGame;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class JunkGames extends JavaPlugin
{
    private static JunkGames plugin;

    public static final Random random = new Random();

    public static JunkGames getPlugin()
    {
        return JunkGames.plugin;
    }

    private final List<IJunkGame> games = new ArrayList<>();

    public void register(@NotNull IJunkGame game)
    {
        for (int i = 0; i < this.games.size(); i ++)
        {
            IJunkGame g = this.games.get(i);

            if (game.getPriority() < g.getPriority())
            {
                this.games.add(i, game);
                break;
            }
        }

        if (this.games.isEmpty() || ! this.games.contains(game))
        {
            this.games.add(0, game);
        }

        Bukkit.getCommandMap().register(game.getName(), this.getName(), new JunkGameCommand(game));
    }

    public IJunkGame getJunkGame(World world)
    {
        List<IJunkGame> filteredGames = new ArrayList<>(this.games.stream().filter(g -> g.contains(world)).toList());
        return filteredGames.isEmpty() ? null : filteredGames.get(0);
    }

    public IJunkGame getJunkGame(@NotNull Player player)
    {
        return this.getJunkGame(player.getWorld());
    }

    public List<IJunkGame> getJunkGames()
    {
        return new ArrayList<>(this.games);
    }

    @Override
    public void onEnable()
    {
        JunkGames.plugin = this;

        Bukkit.getPluginManager().registerEvents(new InventoryListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(), this);

        this.getCommand("junkgames").setExecutor(new JunkGamesCommand());
    }
}
