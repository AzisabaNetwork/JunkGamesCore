package net.azisaba.jg.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class ParentCommand implements IParentCommand
{
    protected final Map<String, ISubcommand> subcommands = new HashMap<>();

    public ParentCommand()
    {
        this.registerSubcommands();
    }

    @Override
    public void registerSubcommand(@NotNull ISubcommand subcommand)
    {
        this.subcommands.put(subcommand.getName(), subcommand);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args)
    {
        if (args.length == 0)
        {
            sender.sendMessage(Component.text(String.format("Correct syntax: /%s <subcommand> [args…]", this.getName())).color(NamedTextColor.RED));
            return true;
        }

        if (! this.subcommands.containsKey(args[0]))
        {
            sender.sendMessage(Component.text(String.format("%s is an unknown subcommand.", args[0])).color(NamedTextColor.RED));
            return true;
        }

        ISubcommand subcommand = this.subcommands.get(args[0]);

        String[] args2 = new String[args.length - 1];
        System.arraycopy(args, 1, args2, 0, args.length - 1);
        subcommand.onCommand(sender, command, label, args2);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args)
    {
        List<String> suggest = new ArrayList<>();

        if (args.length == 1)
        {
            for (ISubcommand subcommand : this.subcommands.values())
            {
                suggest.add(subcommand.getName());
            }

            return suggest;
        }
        else if (this.subcommands.get(args[0]) != null)
        {
            String[] args2 = new String[args.length - 1];
            System.arraycopy(args, 1, args2, 0, args.length - 1);
            return this.subcommands.get(args[0]).onTabComplete(sender, command, args[0], args2);
        }
        else
        {
            return suggest;
        }
    }
}
