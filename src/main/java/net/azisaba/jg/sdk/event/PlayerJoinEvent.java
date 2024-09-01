package net.azisaba.jg.sdk.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayerJoinEvent implements JunkGameEvent
{
    private final Player player;

    public PlayerJoinEvent(Player player)
    {
        this.player = player;
    }

    public Player getPlayer()
    {
        return this.player;
    }

    public Location getLocation()
    {
        return this.player.getLocation();
    }
}
