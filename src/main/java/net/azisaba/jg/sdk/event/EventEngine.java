package net.azisaba.jg.sdk.event;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventEngine
{
    private final Map<Class<? extends JunkGameEvent>, List<Subscriber>> registry = new HashMap<>();

    public void register(@NotNull JunkGameListener listener)
    {
        Class<? extends JunkGameListener> clazz = listener.getClass();

        for (Method method : clazz.getDeclaredMethods())
        {
            Class<?>[] args = method.getParameterTypes();

            if (this.registry.containsKey(method))
            {
                continue;
            }

            if (args.length != 1 || ! JunkGameEvent.class.isAssignableFrom(args[0]))
            {
                continue;
            }

            Class<? extends JunkGameEvent> event = (Class<? extends JunkGameEvent>) args[0];
            List<Subscriber> subscribers = this.registry.containsKey(event) ? this.registry.get(event) : new ArrayList<>();
            subscribers.add(new Subscriber(listener, method));

            this.registry.put(event, subscribers);
        }
    }

    public void call(@NotNull JunkGameEvent event)
    {
        this.registry.forEach((key, value) -> {
            if (key.equals(event.getClass()))
            {
                value.forEach(s -> {
                    try
                    {
                        s.method().invoke(s.instance(), event);
                    }
                    catch (IllegalAccessException | InvocationTargetException ignored) {}
                });
            }
        });
    }

    public Map<Class<? extends JunkGameEvent>, List<Subscriber>> getRegistry()
    {
        return this.registry;
    }

    public record Subscriber(@NotNull JunkGameListener instance, Method method) {}
}
