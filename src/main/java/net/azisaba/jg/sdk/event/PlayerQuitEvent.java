package net.azisaba.jg.sdk.event;

import org.bukkit.entity.Player;

public class PlayerQuitEvent implements JunkGameEvent
{
    private final Player player;

    public PlayerQuitEvent(Player player)
    {
        this.player = player;
    }

    public Player getPlayer()
    {
        return this.player;
    }
}
