package net.azisaba.jg.sdk;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public interface IJunkGameCommand extends CommandExecutor
{
    @NotNull String getName();

    @NotNull IJunkGame getProvider();

    boolean execute(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args);
}
