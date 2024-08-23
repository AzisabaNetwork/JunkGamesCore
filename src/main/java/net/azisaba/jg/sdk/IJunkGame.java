package net.azisaba.jg.sdk;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public interface IJunkGame
{
    @NotNull String getName();

    @NotNull Component getDisplayName();

    @NotNull Material getFavicon();

    @NotNull ItemStack getStack(Player player);

    ArrayList<Component> getLore();

    List<Player> getPlayers();

    List<World> getWorlds();

    List<JunkGameListener> getListeners();

    boolean isPlayer(Player player);

    void onJunkGameCommand(Player player);

    boolean contains(World world);

    void broadcast(Component message);
}
