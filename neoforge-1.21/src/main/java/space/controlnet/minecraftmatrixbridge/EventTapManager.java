package space.controlnet.minecraftmatrixbridge;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Consumer;

public final class EventTapManager extends AbstractEventTapManager<Event> {
    private final IEventBus eventBus;

    public EventTapManager(BridgeService bridgeService, IEventBus eventBus, int maxActiveTaps, long defaultThrottleMs) {
        super(bridgeService, maxActiveTaps, defaultThrottleMs);
        this.eventBus = eventBus;
    }

    @Override
    protected String getLoaderName() {
        return "neoforge-1.21";
    }

    @Override
    protected Class<Event> getEventBaseClass() {
        return Event.class;
    }

    @Override
    protected void registerEventListener(Class<Event> eventClass, Consumer<Event> listener) {
        eventBus.addListener(EventPriority.NORMAL, false, eventClass, listener);
    }
}
