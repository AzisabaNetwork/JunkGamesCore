package net.azisaba.jg.sdk.event

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.entity.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level
import java.util.logging.Logger

/** Marker for SDK events. Note: same simple names as Bukkit events, different package. */
interface JunkGameEvent

/** Marks handler methods of a [JunkGameListener]. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JunkGameHandler

/** Implemented by SDK listener classes. */
interface JunkGameListener

/** A player entered the game (one of its declared worlds). Use this instead of Bukkit's join event. */
class PlayerJoinEvent(val player: Player) : JunkGameEvent {
    val location: Location get() = player.location
}

/** A player left the game (changed world or disconnected). */
class PlayerQuitEvent(val player: Player) : JunkGameEvent

/**
 * A player chatted inside the game. Called asynchronously. By default the message is shown to the
 * game's players only; cancel [bukkitEvent] or change its viewers / renderer to customize.
 */
class PlayerChatEvent(val bukkitEvent: AsyncChatEvent) : JunkGameEvent {
    val sender: Player get() = bukkitEvent.player
    val message: Component get() = bukkitEvent.message()

    /** Plain text of the message (old name kept). */
    val planeMessage: String get() = PlainTextComponentSerializer.plainText().serialize(bukkitEvent.message())
}

/**
 * Reflection-based dispatcher for [JunkGameListener]s (restored from the old SDK).
 * Handlers are one-argument methods taking a [JunkGameEvent] subtype; [JunkGameHandler] is
 * recommended (the old engine did not require it, so neither does this one).
 */
class EventEngine(private val log: Logger? = null) {
    data class Subscriber(val instance: JunkGameListener, val method: Method, val event: Class<out JunkGameEvent>)

    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    fun register(listener: JunkGameListener) {
        var c: Class<*>? = listener.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                val params = m.parameterTypes
                if (params.size != 1 || !JunkGameEvent::class.java.isAssignableFrom(params[0])) continue
                if (subscribers.any { it.method == m && it.instance === listener }) continue
                m.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                subscribers += Subscriber(listener, m, params[0] as Class<out JunkGameEvent>)
            }
            c = c.superclass
        }
    }

    fun unregister(listener: JunkGameListener) {
        subscribers.removeIf { it.instance === listener }
    }

    /** Calls every handler whose parameter type accepts [event] (the old engine matched exact classes only). */
    fun call(event: JunkGameEvent) {
        for (s in subscribers) {
            if (!s.event.isInstance(event)) continue
            try {
                s.method.invoke(s.instance, event)
            } catch (e: InvocationTargetException) {
                log?.log(Level.WARNING, "error in ${s.instance.javaClass.name}#${s.method.name}", e.targetException)
            } catch (e: IllegalAccessException) {
                log?.log(Level.WARNING, "cannot call ${s.method}", e)
            }
        }
    }

    fun listeners(): List<JunkGameListener> = subscribers.map { it.instance }.distinct()

    fun getRegistry(): Map<Class<out JunkGameEvent>, List<Subscriber>> = subscribers.groupBy { it.event }
}
