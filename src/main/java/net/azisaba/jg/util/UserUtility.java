package net.azisaba.jg.util;

import net.azisaba.jg.user.IUser;
import net.azisaba.jg.user.Sara;
import org.bukkit.entity.Player;

public class UserUtility
{
    public static Sara calculateSara(Player player)
    {
        if (player.isOp())
        {
            return Sara.ADMIN;
        }
        else if (player.hasPermission("group.nitro"))
        {
            return Sara.NITRO;
        }
        else if (player.hasPermission("group.gamingsara"))
        {
            return Sara.GAMING;
        }
        else if (player.hasPermission("group.50000yen"))
        {
            return Sara.$50000YEN;
        }
        else if (player.hasPermission("group.10000yen"))
        {
            return Sara.$10000YEN;
        }
        else if (player.hasPermission("group.5000yen"))
        {
            return Sara.$5000YEN;
        }
        else if (player.hasPermission("group.2000yen"))
        {
            return Sara.$2000YEN;
        }
        else if (player.hasPermission("group.1000yen"))
        {
            return Sara.$1000YEN;
        }
        else if (player.hasPermission("group.500yen"))
        {
            return Sara.$500YEN;
        }
        else if (player.hasPermission("group.100yen"))
        {
            return Sara.$100YEN;
        }
        else
        {
            return Sara.DEFAULT;
        }
    }

    public static boolean isModerator(IUser user)
    {
        return 10 <= user.getSara().level;
    }

    public static boolean isAdmin(IUser user)
    {
        return 11 <= user.getSara().level;
    }
}
