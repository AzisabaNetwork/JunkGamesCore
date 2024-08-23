package net.azisaba.jg.ui;

import net.azisaba.jg.JunkGames;
import net.azisaba.jg.sdk.IJunkGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class JunkGamesUI extends InventoryUI
{
    public JunkGamesUI(Player player)
    {
        super(player, Bukkit.createInventory(null, 36, Component.text("Junk Games")));

        List<IJunkGame> games = JunkGames.getPlugin().getJunkGames();

        for (int i = 9; i < 27; i ++)
        {
            if ((i - 9) < games.size())
            {
                IJunkGame game = games.get(i - 9);
                this.registerListener(i, game.getStack(this.getPlayer()), game.getName(), true);
                continue;
            }

            ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(Component.text(""));
            stack.setItemMeta(meta);

            this.inventory.setItem(i, stack);
        }

        ItemStack closeStack = new ItemStack(Material.OAK_DOOR);
        ItemMeta closeMeta = closeStack.getItemMeta();
        closeMeta.displayName(Component.text("閉じる").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        closeMeta.lore(List.of(Component.text("この画面を閉じます。").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        closeStack.setItemMeta(closeMeta);
        this.inventory.setItem(26, closeStack);
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        super.onClick(event);
        event.setCancelled(true);

        if (event.getSlot() == 26)
        {
            this.player.closeInventory();
        }
    }
}
