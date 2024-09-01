package net.azisaba.jg.listener;

import net.azisaba.jg.ui.IInventoryUI;
import net.azisaba.jg.ui.InventoryUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class InventoryListener implements Listener
{
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event)
    {
        Player player = (Player) event.getWhoClicked();
        IInventoryUI inventoryUI = InventoryUI.getInstance(player);

        if (inventoryUI != null)
        {
            inventoryUI.onClick(event);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event)
    {
        Player player = (Player) event.getPlayer();
        IInventoryUI inventoryUI = InventoryUI.getInstance(player);

        if (inventoryUI != null)
        {
            inventoryUI.onClose(event);
        }
    }
}
