package net.azisaba.jg.compat;

import net.azisaba.jg.JunkGames;
import net.azisaba.jg.sdk.IJunkGame;
import net.azisaba.jg.sdk.JunkGame;
import net.azisaba.jg.sdk.JunkGameCommand;
import net.azisaba.jg.sdk.event.JunkGameHandler;
import net.azisaba.jg.sdk.event.JunkGameListener;
import net.azisaba.jg.sdk.event.PlayerChatEvent;
import net.azisaba.jg.sdk.event.PlayerJoinEvent;
import net.azisaba.jg.ui.InventoryUI;
import net.azisaba.jg.util.Typing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Written like a game plugin for JunkGamesCore 0.x (Java). It only has to compile: it proves the
 * restored SDK keeps the old source API.
 */
public final class OldStyleGame extends JunkGame {
    @Override
    public @NotNull Component getDisplayName() {
        return Component.text("Old Style").color(NamedTextColor.GREEN);
    }

    @Override
    public @NotNull Material getFavicon() {
        return Material.STONE;
    }

    @Override
    public @NotNull String getCategory() {
        return "PvP Classic";
    }

    @Override
    public @NotNull List<Component> getLore() {
        return List.of(Component.text("old"));
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public void onJunkGameCommand(@NotNull Player player) {
        player.teleport(JunkGames.spawn);
    }

    @Override
    public void onEnable() {
        this.addWorld("world");
        this.addListener(new Listener());
        IJunkGame game = JunkGames.getPlugin().getJunkGame(Bukkit.getWorlds().get(0));
        if (game != null && game.isPlayer(Bukkit.getPlayer("x"))) this.broadcast(Component.text("hi"));
    }

    public static final class Listener implements JunkGameListener {
        @JunkGameHandler
        public void onJoin(PlayerJoinEvent event) {
            event.getPlayer().teleport(event.getLocation());
        }

        @JunkGameHandler
        public void onChat(PlayerChatEvent event) {
            event.getSender().sendMessage(event.getPlaneMessage());
            event.getBukkitEvent().setCancelled(true);
        }
    }

    public static final class ExampleCommand extends JunkGameCommand {
        private final OldStyleGame game;

        public ExampleCommand(OldStyleGame game) {
            this.game = game;
        }

        @Override
        public @NotNull String getName() {
            return "example";
        }

        @Override
        public @NotNull IJunkGame getProvider() {
            return game;
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            return true;
        }
    }

    public static final class Menu extends InventoryUI {
        public Menu(Player player) {
            super(player, Bukkit.createInventory(null, 9));
            this.addListener(0, new org.bukkit.inventory.ItemStack(Material.STONE), "spawn");
            this.inventory.setItem(1, null);
        }
    }

    public static final class Input extends Typing {
        public Input(Player player) {
            super(player);
        }

        @Override
        public void onTyped(@NotNull String string) {
            super.onTyped(string);
            this.player.sendMessage(getConfirmCode(4));
        }
    }
}
