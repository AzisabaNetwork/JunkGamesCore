package net.azisaba.jg.ui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public interface IInventoryUI
{
    Inventory getInventory();

    void onClick(@NotNull InventoryClickEvent event);

    void onClose(@NotNull InventoryCloseEvent event);
}
