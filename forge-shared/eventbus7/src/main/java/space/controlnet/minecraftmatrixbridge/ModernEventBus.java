package space.controlnet.minecraftmatrixbridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reflection boundary for EventBus 7, also testable without a Minecraft runtime. */
final class ModernEventBus {
    private final Class<?> eventType;
    private final Class<?> busType;
    private final byte normalPriority;
    private final Method addListener;

    static ModernEventBus load(ClassLoader loader) {
        try {
            return new ModernEventBus(
                    Class.forName("net.minecraftforge.eventbus.internal.Event", true, loader),
                    Class.forName("net.minecraftforge.eventbus.api.bus.EventBus", true, loader),
                    priority(loader, "NORMAL"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Forge EventBus 7 API", e);
        }
    }

    /** Reads a constant of EventBus 7's {@code Priority}, e.g. "LOWEST". */
    static byte priority(ClassLoader loader, String name) throws ReflectiveOperationException {
        return Class.forName("net.minecraftforge.eventbus.api.listener.Priority", true, loader).getField(name).getByte(null);
    }

    ModernEventBus(Class<?> eventType, Class<?> busType, byte normalPriority) {
        this.eventType = eventType;
        this.busType = busType;
        this.normalPriority = normalPriority;
        try {
            addListener = busType.getMethod("addListener", byte.class, Consumer.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Forge EventBus 7 listener API", e);
        }
    }

    Class<?> eventBaseClass() {
        return eventType;
    }

    static String eventName(String name) {
        // These parent interfaces no longer extend Event, so the bytecode index
        // cannot discover their former aliases. Keep existing tap commands valid.
        String simple = name.replace("TickEvent.", "").replace("TickEvent$", "");
        return switch (simple) {
            case "TickEvent" -> "net.minecraftforge.event.TickEvent";
            case "ServerTickEvent", "PlayerTickEvent", "LevelTickEvent", "ClientTickEvent", "RenderTickEvent" ->
                    "net.minecraftforge.event.TickEvent$" + simple;
            default -> name;
        };
    }

    boolean isEventClass(Class<?> type) {
        if (eventType.isAssignableFrom(type)) {
            return true;
        }
        // Forge's new sealed tick interfaces are event families, not Event subtypes.
        if (!type.isInterface() || !type.isSealed()) {
            return false;
        }
        for (Class<?> child : type.getPermittedSubclasses()) {
            if (!isEventClass(child)) {
                return false;
            }
        }
        return type.getPermittedSubclasses().length > 0;
    }

    void listen(Class<?> type, Consumer<Object> listener) {
        listen(type, listener, normalPriority);
    }

    void listen(Class<?> type, Consumer<Object> listener, byte priority) {
        if (!isEventClass(type)) {
            throw new IllegalArgumentException("Not a Forge event: " + type.getName());
        }
        List<Object> buses = new ArrayList<>();
        collectBuses(type, buses);
        try {
            for (Object bus : buses) {
                // Normal consumers neither cancel events nor observe already-cancelled ones.
                // EventBus 7's boolean overload means alwaysCancelling, not receiveCancelled.
                addListener.invoke(bus, priority, listener);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not register Forge event: " + type.getName(), e);
        }
    }

    /**
     * Registers a read-only listener that runs after every other listener (MONITOR), so it sees an
     * event as the other mods left it, and only when none of them cancelled it.
     */
    void monitor(Class<?> type, Consumer<Object> listener) {
        if (!isEventClass(type)) {
            throw new IllegalArgumentException("Not a Forge event: " + type.getName());
        }
        List<Object> buses = new ArrayList<>();
        collectBuses(type, buses);
        try {
            ClassLoader loader = busType.getClassLoader();
            Class<?> cancellableBus = Class.forName("net.minecraftforge.eventbus.api.bus.CancellableEventBus", true, loader);
            Class<?> monitorType = Class.forName("net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer", true, loader);
            Method addMonitor = cancellableBus.getMethod("addListener", monitorType);
            Object monitor = Proxy.newProxyInstance(monitorType.getClassLoader(), new Class<?>[] {monitorType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "MatrixBridge monitor for " + type.getName();
                        default -> {
                            // accept(event, wasCancelled)
                            if (!(Boolean) args[1]) {
                                listener.accept(args[0]);
                            }
                            yield null;
                        }
                    });
            Consumer<Object> keepPosting = ignored -> { };
            for (Object bus : buses) {
                if (!cancellableBus.isInstance(bus)) {
                    throw new IllegalStateException("Not a cancellable event: " + type.getName());
                }
                addMonitor.invoke(bus, monitor);
                // EventBus 7 skips posting (monitors included) while no ordinary listener exists.
                addListener.invoke(bus, normalPriority, keepPosting);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not monitor Forge event: " + type.getName(), e);
        }
    }

    private void collectBuses(Class<?> type, List<Object> buses) {
        try {
            // Do not inherit a parent's BUS: that would subscribe to unrelated siblings.
            var field = type.getDeclaredField("BUS");
            if (!Modifier.isStatic(field.getModifiers()) || !busType.isAssignableFrom(field.getType())) {
                throw new IllegalStateException("Invalid event BUS: " + type.getName());
            }
            Object bus = field.get(null);
            if (!busType.isInstance(bus)) {
                throw new IllegalStateException("Missing event BUS: " + type.getName());
            }
            if (!buses.contains(bus)) {
                buses.add(bus);
            }
        } catch (NoSuchFieldException absent) {
            if (!type.isInterface() || !type.isSealed()) {
                throw new IllegalStateException("Event has no own BUS: " + type.getName(), absent);
            }
            for (Class<?> child : type.getPermittedSubclasses()) {
                collectBuses(child, buses);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Inaccessible event BUS: " + type.getName(), e);
        }
    }
}
