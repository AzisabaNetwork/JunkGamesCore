package net.azisaba.jg.sdk;

import net.azisaba.jg.sdk.event.JunkGameEvent;
import net.azisaba.jg.sdk.event.JunkGameListener;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface IJunkGame
{
    @NotNull String getName();

    @NotNull Component getDisplayName();

    @NotNull Material getFavicon();

    @NotNull String getCategory();

    int getPriority();

    @NotNull ItemStack getStack();

    List<Component> getLore();

    List<Player> getPlayers();

    List<World> getWorlds();

    List<JunkGameListener> getListeners();

    List<IJunkGameCommand> getCommands();

    boolean isPlayer(Player player);

    void onJunkGameCommand(@NotNull Player player);

    void call(@NotNull JunkGameEvent event);

    boolean contains(World world);

    void broadcast(Component message);
}
