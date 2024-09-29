package net.azisaba.jg.ui;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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

public class InventoryUI implements IInventoryUI
{
    private static final List<IInventoryUI> instances = new ArrayList<>();

    public static IInventoryUI getInstance(Inventory inventory)
    {
        List<IInventoryUI> filteredInstances = InventoryUI.instances.stream().filter(i -> i.getInventory() == inventory).toList();
        return filteredInstances.isEmpty() ? null : filteredInstances.get(filteredInstances.size() - 1);
    }

    public static IInventoryUI getInstance(Player player)
    {
        return InventoryUI.getInstance(player.getOpenInventory().getTopInventory());
    }

    @Deprecated
    public static List<IInventoryUI> getInstances()
    {
        return InventoryUI.instances;
    }

    protected final Player player;
    protected final Inventory inventory;

    protected final Map<Integer, ListenerSlot> listeners = new HashMap<>();

    public InventoryUI(@NotNull Player player, @NotNull Inventory inventory)
    {
        this.player = player;
        this.inventory = inventory;

        if (InventoryUI.getInstance(player) != null)
        {
            InventoryUI.getInstance(player).onClose(new InventoryCloseEvent(player.getOpenInventory()));
        }

        this.player.openInventory(this.inventory);
        InventoryUI.instances.add(this);
    }

    @Override
    public Inventory getInventory()
    {
        return this.inventory;
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);

        if (event.getCurrentItem() == null)
        {
            return;
        }

        if (! this.listeners.containsKey(event.getSlot()))
        {
            return;
        }

        ListenerSlot listener = this.listeners.get(event.getSlot());
        CommandSender sender = listener.serverside() ? Bukkit.getConsoleSender() : event.getWhoClicked();

        Bukkit.dispatchCommand(sender, listener.command());
    }

    @Override
    public void onClose(InventoryCloseEvent event)
    {
        InventoryUI.instances.remove(this);
    }

    protected void addListener(int index, @NotNull ItemStack stack, @NotNull String command, boolean serverside)
    {
        this.inventory.setItem(index, stack);
        this.listeners.put(index, new ListenerSlot(command, serverside));
    }

    protected void addListener(int index, @NotNull ItemStack stack, @NotNull String command)
    {
        this.addListener(index, stack, command, false);
    }

    public record ListenerSlot(String command, boolean serverside) {}
}
