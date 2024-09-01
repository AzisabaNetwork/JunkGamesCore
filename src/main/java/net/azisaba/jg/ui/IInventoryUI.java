package net.azisaba.jg.ui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

public interface IInventoryUI
{
    Inventory getInventory();

    void onClick(InventoryClickEvent event);

    void onClose(InventoryCloseEvent event);
}
