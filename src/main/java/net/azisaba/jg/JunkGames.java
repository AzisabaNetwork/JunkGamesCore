package net.azisaba.jg;

import net.azisaba.jg.command.JunkGameCommand;
import net.azisaba.jg.command.JunkGamesCommand;
import net.azisaba.jg.listener.InventoryListener;
import net.azisaba.jg.listener.PlayerListener;
import net.azisaba.jg.sdk.IJunkGame;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class JunkGames extends JavaPlugin
{
    private static JunkGames plugin;

    public static World lobby;

    public static Location spawn;

    public static final Random random = new Random();

    private final List<IJunkGame> games = new ArrayList<>();

    public static JunkGames getPlugin()
    {
        return JunkGames.plugin;
    }

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

        this.saveDefaultConfig();

        JunkGames.lobby = Bukkit.getWorld("lobby");

        if (JunkGames.lobby == null)
        {
            WorldCreator creator = new WorldCreator("lobby");

            creator.generator(new ChunkGenerator()
            {
                @Override
                public @NotNull ChunkData generateChunkData(@NotNull World world, @NotNull Random random, int x, int z, @NotNull BiomeGrid biome)
                {
                    ChunkData chunkData = this.createChunkData(world);

                    for (int x2 = 0; x2 < 16; x2 ++)
                    {
                        for (int z2 = 0; z2 < 16; z2 ++)
                        {
                            biome.setBiome(x2, z2, Biome.PLAINS);
                        }
                    }

                    return chunkData;
                }
            });

            JunkGames.lobby = creator.createWorld();

            if (JunkGames.lobby == null)
            {
                return;
            }

            JunkGames.lobby.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            JunkGames.lobby.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            JunkGames.lobby.setTime(6000);
        }

        JunkGames.spawn = new Location(JunkGames.lobby,
                this.getConfig().getDouble("spawn.x"),
                this.getConfig().getDouble("spawn.y"),
                this.getConfig().getDouble("spawn.z"),
                (float) this.getConfig().getDouble("spawn.yaw"),
                (float) this.getConfig().getDouble("spawn.pitch"));
    }
}
