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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class JunkGamesUI extends InventoryUI
{
    private final BukkitRunnable runnable;

    private final List<IJunkGame> games = JunkGames.getPlugin().getJunkGames();

    private final int[] slots = new int[] {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    public JunkGamesUI(@NotNull Player player)
    {
        super(player, Bukkit.createInventory(null, 54, Component.text("Junk Games")));

        ItemStack lobbyStack = new ItemStack(Material.BOOKSHELF);
        ItemMeta lobbyMeta = lobbyStack.getItemMeta();
        lobbyMeta.displayName(Component.text("メインロビー").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        lobbyMeta.lore(List.of(Component.text("メインロビーに戻る").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        lobbyStack.setItemMeta(lobbyMeta);
        this.inventory.setItem(4, lobbyStack);

        ItemStack randomStack = new ItemStack(Material.STRING);
        ItemMeta randomMeta = randomStack.getItemMeta();
        randomMeta.displayName(Component.text("ランダムなゲーム").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        randomMeta.lore(List.of(Component.text("ランダムなゲームに参加").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        randomStack.setItemMeta(randomMeta);
        this.inventory.setItem(48, randomStack);

        ItemStack closeStack = new ItemStack(Material.OAK_DOOR);
        ItemMeta closeMeta = closeStack.getItemMeta();
        closeMeta.displayName(Component.text("閉じる").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        closeMeta.lore(List.of(Component.text("この画面を閉じます").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        closeStack.setItemMeta(closeMeta);
        this.inventory.setItem(49, closeStack);

        ItemStack reportStack = new ItemStack(Material.PAPER);
        ItemMeta reportMeta = reportStack.getItemMeta();
        reportMeta.displayName(Component.text("通報").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        reportMeta.lore(List.of(Component.text("ルール違反ですか？レポートを作成しましょう…").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        reportStack.setItemMeta(reportMeta);
        this.inventory.setItem(50, reportStack);

        this.runnable = new BukkitRunnable()
        {
            private int i = 0;
            private boolean b;

            @Override
            public void run()
            {
                if (games.isEmpty())
                {
                    this.cancel();
                    return;
                }

                if (games.size() <= i)
                {
                    i = 0;
                }

                for (int i = 0; i < slots.length; i ++)
                {
                    if (games.size() <= i)
                    {
                        break;
                    }

                    IJunkGame game = games.get(i);
                    ItemStack gameStack = game.getStack();

                    List<Component> lore = gameStack.lore();

                    if (lore == null)
                    {
                        lore = new ArrayList<>();
                    }

                    lore.add(Component.text().build());

                    if (game.isPlayer(player))
                    {
                        lore.add(Component.text("PLAYING").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
                    }
                    else
                    {
                        lore.add(Component.text((this.b ? "▶" : "  ") + " クリックして接続").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                    }

                    lore.add(Component.text(game.getPlayers().size() + " 人がプレイ中！").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    gameStack.lore(lore);

                    addListener(slots[0], gameStack, "junkgames:" + game.getName());
                }

                randomStack.setType(games.get(this.i).getFavicon());
                inventory.setItem(48, randomStack);

                this.i ++;
                this.b = ! this.b;
            }
        };

        this.runnable.runTaskTimer(JunkGames.getPlugin(), 0L, 10L);
    }

    @Override
    public void onClick(@NotNull InventoryClickEvent event)
    {
        super.onClick(event);

        if (event.getSlot() == 4)
        {
            this.player.teleport(JunkGames.spawn);
        }

        if (event.getSlot() == 48)
        {
            List<IJunkGame> games = this.games.stream().filter(game -> ! game.isPlayer(this.player)).toList();

            if (games.isEmpty())
            {
                return;
            }

            IJunkGame game = games.get(JunkGames.random.nextInt(games.size()));
            Bukkit.dispatchCommand(this.player, "junkgames:" + game.getName());
        }

        if (event.getSlot() == 49)
        {
            this.player.closeInventory();
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event)
    {
        super.onClose(event);
        this.runnable.cancel();
    }
}
