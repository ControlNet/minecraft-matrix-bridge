package space.controlnet.minecraftmatrixbridge;

import java.util.function.Consumer;

public final class EventTapManager extends AbstractEventTapManager<Object> {
    private final ForgeEventBus eventBus;

    EventTapManager(BridgeService bridgeService, ForgeEventBus eventBus, int maxActiveTaps, long defaultThrottleMs) {
        super(bridgeService, maxActiveTaps, defaultThrottleMs);
        this.eventBus = eventBus;
    }

    @Override
    protected String getLoaderName() {
        return "forge-1.19plus";
    }

    @Override
    public String handleCommand(String[] args) {
        if (args != null && args.length > 1 && ("on".equalsIgnoreCase(args[0]) || "off".equalsIgnoreCase(args[0]))) {
            args = args.clone();
            args[1] = eventBus.eventName(args[1]);
        }
        return super.handleCommand(args);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Class<Object> getEventBaseClass() {
        return (Class<Object>) eventBus.eventBaseClass();
    }

    @Override
    protected boolean isEventClass(Class<?> eventClass) {
        return eventBus.isEventClass(eventClass);
    }

    @Override
    protected Object getListenerRegistryKey() {
        return eventBus;
    }

    @Override
    protected void registerEventListener(Class<Object> eventClass, Consumer<Object> listener) {
        eventBus.listen(eventClass, listener);
    }
}
