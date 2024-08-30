package net.azisaba.jg.sdk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class JunkGameCommand extends Command
{
    private final IJunkGame game;

    public JunkGameCommand(@NotNull IJunkGame game)
    {
        super(game.getName());
        this.game = game;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args)
    {
        if (! (sender instanceof Player player))
        {
            sender.sendMessage(Component.text("Please run this from within the game.").color(NamedTextColor.RED));
            return true;
        }

        if (args.length != 0)
        {
            sender.sendMessage(Component.text(String.format("Correct syntax: /%s", this.getName())).color(NamedTextColor.RED));
            return true;
        }

        if (this.game.isPlayer(player))
        {
            sender.sendMessage(Component.text(String.format("あなたは既に %S に接続しています", this.game.getName())).color(NamedTextColor.RED));
            return true;
        }

        this.game.onJunkGameCommand(player);
        return true;
    }
}
