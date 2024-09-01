package net.azisaba.jg.sdk;

import net.azisaba.jg.JunkGames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public abstract class JunkGameCommand implements IJunkGameCommand
{
    public JunkGameCommand()
    {
        this.getProvider().getCommands().add(this);
    }

    @Override
    @Deprecated
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args)
    {
        if (sender instanceof Player player)
        {
            IJunkGame game = JunkGames.getPlugin().getJunkGame(player);

            if (game != this.getProvider())
            {
                sender.sendMessage(Component.text("ここではこのコマンドを使用することはできません").color(NamedTextColor.RED));
                return true;
            }
        }

        return this.execute(sender, command, label, args);
    }
}
