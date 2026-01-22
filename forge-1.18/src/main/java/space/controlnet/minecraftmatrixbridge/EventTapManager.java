package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.function.Consumer;

public final class EventTapManager extends AbstractEventTapManager<Event> {
    private final IEventBus eventBus;

    public EventTapManager(BridgeService bridgeService, IEventBus eventBus, int maxActiveTaps, long defaultThrottleMs) {
        super(bridgeService, maxActiveTaps, defaultThrottleMs);
        this.eventBus = eventBus;
    }

    @Override
    protected String getLoaderName() {
        return "forge-1.18";
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
