package net.azisaba.jg.sdk;

import net.azisaba.jg.JunkGames;
import net.azisaba.jg.sdk.event.EventEngine;
import net.azisaba.jg.sdk.event.JunkGameEvent;
import net.azisaba.jg.sdk.event.JunkGameListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public abstract class JunkGame extends JavaPlugin implements IJunkGame
{
    private static JunkGame plugin;

    public static JunkGame getPlugin()
    {
        return JunkGame.plugin;
    }

    public static Logger getPluginLogger()
    {
        return JunkGame.getPlugin().getLogger();
    }

    protected final JunkGames junkGames;

    protected final EventEngine event = new EventEngine();

    protected final List<World> worlds = new ArrayList<>();
    protected final List<IJunkGameCommand> commands = new ArrayList<>();

    public JunkGame()
    {
        this.junkGames = (JunkGames) Bukkit.getPluginManager().getPlugin("JunkGames");

        if (this.junkGames != null)
        {
            this.junkGames.register(this);
        }
    }

    public JunkGames getJunkGames()
    {
        return this.junkGames;
    }

    @Override
    public @NotNull ItemStack getStack()
    {
        ItemStack stack = new ItemStack(this.getFavicon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(this.getDisplayName().decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);

        List<Component> lore = new ArrayList<>();

        lore.add(Component.text(this.getCategory()).color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text().build());
        lore.addAll(this.getLore());

        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    @Override
    public List<Player> getPlayers()
    {
        List<Player> players = new ArrayList<>();
        this.getWorlds().forEach(w -> players.addAll(w.getPlayers()));
        return players;
    }

    @Override
    public List<World> getWorlds()
    {
        return new ArrayList<>(this.worlds);
    }

    @Override
    public List<JunkGameListener> getListeners()
    {
        List<JunkGameListener> listeners = new ArrayList<>();
        this.event.getRegistry().values().forEach(i -> i.forEach(j -> listeners.add(j.instance())));
        return listeners;
    }

    @Override
    public List<IJunkGameCommand> getCommands()
    {
        return this.commands;
    }

    public void addWorld(@NotNull World world)
    {
        this.worlds.add(world);
    }

    public void addWorld(@NotNull String name)
    {
        World world = Bukkit.getWorld(name);

        if (world != null)
        {
            this.addWorld(world);
        }
    }

    protected void addListener(@NotNull JunkGameListener listener)
    {
        this.event.register(listener);

        if (listener instanceof Listener bukkitListener)
        {
            Bukkit.getPluginManager().registerEvents(bukkitListener, this);
        }
    }

    @Override
    public boolean isPlayer(Player player)
    {
        return this.getPlayers().contains(player);
    }

    @Override
    public void onEnable()
    {
        JunkGame.plugin = this;
    }

    @Override
    public void call(@NotNull JunkGameEvent event)
    {
        this.event.call(event);
    }

    @Override
    public boolean contains(World world)
    {
        return this.worlds.contains(world);
    }

    @Override
    public void broadcast(@NotNull Component message)
    {
        this.getPlayers().forEach(p -> p.sendMessage(message));
    }
}
