package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.common.EventBusMigrationHelper;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.eventbus.internal.Event;

import java.lang.reflect.Field;
import java.util.function.Consumer;

public final class EventTapManager extends AbstractEventTapManager<Event> {
    private final EventBusMigrationHelper eventBus;

    public EventTapManager(BridgeService bridgeService, EventBusMigrationHelper eventBus, int maxActiveTaps, long defaultThrottleMs) {
        super(bridgeService, maxActiveTaps, defaultThrottleMs);
        this.eventBus = eventBus;
    }

    @Override
    protected String getLoaderName() {
        return "forge-26";
    }

    @Override
    protected Class<Event> getEventBaseClass() {
        return Event.class;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void registerEventListener(Class<Event> eventClass, Consumer<Event> listener) {
        try {
            Field busField = eventClass.getField("BUS");
            Object busObject = busField.get(null);
            if (busObject instanceof EventBus<?> typedBus) {
                ((EventBus<Event>) typedBus).addListener(Priority.NORMAL, listener);
                return;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the explicit failure below.
        }

        throw new IllegalStateException("Forge 26 event class does not expose a compatible BUS field: " + eventClass.getName());
    }
}
