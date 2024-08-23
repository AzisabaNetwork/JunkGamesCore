package net.azisaba.jg.ui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class InventoryUI
{
    protected static final List<InventoryUI> instances = new ArrayList<>();

    public static InventoryUI getInstance(Inventory inventory)
    {
        List<InventoryUI> filteredInstances = new ArrayList<>(InventoryUI.instances.stream().filter(i -> i.getInventory() == inventory).toList());
        return filteredInstances.isEmpty() ? null : filteredInstances.get(0);
    }

    protected final Player player;
    protected final Inventory inventory;
    protected final Map<Integer, String> clientListeners = new HashMap<>();
    protected final Map<Integer, String> serverListeners = new HashMap<>();

    public InventoryUI(Player player, Inventory inventory)
    {
        this.player = player;
        this.inventory = inventory;

        Inventory currentInventory = this.player.getOpenInventory().getTopInventory();

        if (InventoryUI.getInstance(currentInventory) != null)
        {
            InventoryUI.getInstance(currentInventory).onClose(new InventoryCloseEvent(player.getOpenInventory()));
        }

        this.player.openInventory(this.inventory);
        InventoryUI.instances.add(this);
    }

    public Player getPlayer()
    {
        return this.player;
    }

    public Inventory getInventory()
    {
        return this.inventory;
    }

    public void registerListener(int index, @NotNull ItemStack stack, @NotNull String command, boolean clientSide)
    {
        if (clientSide)
        {
            this.clientListeners.put(index, command);
        }
        else
        {
            this.serverListeners.put(index, command);
        }

        this.inventory.setItem(index, stack);
    }

    public void onClick(InventoryClickEvent event)
    {
        if (event.getCurrentItem() == null)
        {
            return;
        }

        if (this.clientListeners.containsKey(event.getSlot()))
        {
            event.getWhoClicked().closeInventory();
            Bukkit.dispatchCommand(this.player, this.clientListeners.get(event.getSlot()));
        }

        if (this.serverListeners.containsKey(event.getSlot()))
        {
            event.getWhoClicked().closeInventory();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), this.serverListeners.get(event.getSlot()));
        }
    }

    public void onClose(InventoryCloseEvent event)
    {
        InventoryUI.instances.remove(this);
    }
}
