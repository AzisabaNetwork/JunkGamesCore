package net.azisaba.jg.listener;

import net.azisaba.jg.ui.InventoryUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

public class InventoryListener implements Listener
{
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event)
    {
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        InventoryUI ui = InventoryUI.getInstance(inventory);

        if (ui != null)
        {
            ui.onClick(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event)
    {
        Player player = (Player) event.getPlayer();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        InventoryUI ui = InventoryUI.getInstance(inventory);

        if (ui != null)
        {
            ui.onClose(event);
        }
    }
}
