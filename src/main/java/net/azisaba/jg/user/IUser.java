package net.azisaba.jg.user;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public interface IUser
{
    UUID getId();

    default Component getName()
    {
        return this.getSara().role.append(Component.text(this.getPlaneName(), this.getSara().color));
    }

    default String getPlaneName()
    {
        return this.getAsOfflinePlayer().getName();
    }

    Sara getSara();

    default Player getAsPlayer()
    {
        return Bukkit.getPlayer(this.getId());
    }

    default OfflinePlayer getAsOfflinePlayer()
    {
        return Bukkit.getOfflinePlayer(this.getId());
    }

    default boolean isOnline()
    {
        return Bukkit.getOfflinePlayer(this.getId()).isOnline();
    }
}
