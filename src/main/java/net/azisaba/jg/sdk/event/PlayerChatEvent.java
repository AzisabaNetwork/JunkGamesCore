package net.azisaba.jg.sdk.event;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.entity.Player;

public class PlayerChatEvent implements JunkGameEvent
{
    private AsyncChatEvent event;

    public PlayerChatEvent(AsyncChatEvent event)
    {
        this.event = event;
    }

    public Player getSender()
    {
        return this.event.getPlayer();
    }

    public Component getMessage()
    {
        return this.event.message();
    }

    public String getPlaneMessage()
    {
        TextComponent message = (TextComponent) this.event.message();
        return message.content();
    }

    public AsyncChatEvent getBukkitEvent()
    {
        return this.event;
    }
}
