package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.common.EventBusMigrationHelper;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.eventbus.internal.Event;

import java.util.function.Consumer;

/**
 * Forge 26 (EventBus 7) taps. Shares {@link ModernEventBus} with Forge 1.21 so that
 * sealed event families such as {@code TickEvent$ServerTickEvent}, which are interfaces
 * rather than Event subtypes, can be tapped, and each event's own BUS is used.
 */
public final class EventTapManager extends AbstractEventTapManager<Object> {
    private final EventBusMigrationHelper eventBus;
    private final ModernEventBus bus = new ModernEventBus(Event.class, EventBus.class, Priority.NORMAL);

    public EventTapManager(BridgeService bridgeService, EventBusMigrationHelper eventBus, int maxActiveTaps, long defaultThrottleMs) {
        super(bridgeService, maxActiveTaps, defaultThrottleMs);
        this.eventBus = eventBus;
    }

    @Override
    protected String getLoaderName() {
        return "forge-26";
    }

    @Override
    public String handleCommand(String[] args) {
        if (args != null && args.length > 1 && ("on".equalsIgnoreCase(args[0]) || "off".equalsIgnoreCase(args[0]))) {
            args = args.clone();
            args[1] = ModernEventBus.eventName(args[1]);
        }
        return super.handleCommand(args);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<Object> getEventBaseClass() {
        return (Class<Object>) bus.eventBaseClass();
    }

    @Override
    protected boolean isEventClass(Class<?> eventClass) {
        return bus.isEventClass(eventClass);
    }

    @Override
    protected Object getListenerRegistryKey() {
        return eventBus;
    }

    @Override
    protected void registerEventListener(Class<Object> eventClass, Consumer<Object> listener) {
        bus.listen(eventClass, listener);
    }
}
