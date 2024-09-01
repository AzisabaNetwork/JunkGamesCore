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

    int getPriority();

    @NotNull ItemStack getStack(Player player);

    List<Component> getLore();

    List<Player> getPlayers();

    List<World> getWorlds();

    List<JunkGameListener> getListeners();

    List<IJunkGameCommand> getCommands();

    boolean isPlayer(Player player);

    void onJunkGameCommand(Player player);

    void call(JunkGameEvent event);

    boolean contains(World world);

    void broadcast(Component message);
}
