package net.azisaba.jg.sdk;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public abstract class JunkGameListener
{
    public void onPlayerJoin(Player player) {}

    public void onPlayerQuit(Player player) {}

    public void onPlayerDeath(Player player) {}

    public void onChat(Player sender, Component message) {}
}
