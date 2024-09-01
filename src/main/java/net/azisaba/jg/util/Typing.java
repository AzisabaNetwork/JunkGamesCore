package net.azisaba.jg.util;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public abstract class Typing
{
    private static final ArrayList<Typing> instances = new ArrayList<>();

    public static Typing getInstance(Player player)
    {
        ArrayList<Typing> filteredInstances = new ArrayList<>(Typing.instances.stream().filter(i -> i.getPlayer() == player).toList());
        return filteredInstances.isEmpty() ? null : filteredInstances.get(0);
    }

    protected final Player player;

    public Typing(@NotNull Player player)
    {
        this.player = player;
        this.player.closeInventory();
        this.init();

        Typing currentTask = Typing.getInstance(player);

        if (currentTask != null)
        {
            Typing.instances.remove(currentTask);
        }

        Typing.instances.add(this);
    }

    public void init() {}

    public Player getPlayer()
    {
        return this.player;
    }

    public void onTyped(@NotNull String string)
    {
        Typing.instances.remove(this);
    }
}
