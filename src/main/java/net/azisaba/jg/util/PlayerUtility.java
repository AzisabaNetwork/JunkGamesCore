package net.azisaba.jg.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PlayerUtility
{
    public static void giveItemStack(Player player, List<ItemStack> stacks)
    {
        Inventory inventory = player.getInventory();

        List<ItemStack> remaining = new ArrayList<>();

        stacks.forEach(stack -> {
            if (stack != null && 0 < stack.getAmount())
            {
                remaining.addAll(Arrays.asList(inventory.addItem(stack).values().toArray(new ItemStack[0])));
            }
        });

        remaining.forEach(stack -> {
            player.getWorld().dropItem(player.getLocation(), stack);
        });
    }

    public static void giveItemStack(Player player, ItemStack stack)
    {
        PlayerUtility.giveItemStack(player, Collections.singletonList(stack));
    }
}
