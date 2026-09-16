package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AbstractEventTapManagerTest {
    // Synthetic events and listener registry model parent/subtype bus dispatch.
    public static class ParentEvent {}
    public static class ChildEvent extends ParentEvent {}

    private static class TestManager extends AbstractEventTapManager<Object> {
        final Map<Class<?>, Consumer<Object>> listeners = new HashMap<>();

        TestManager() {
            super(new BridgeService(), 10, 1);
        }

        @Override protected String getLoaderName() { return "test"; }
        @Override protected Class<Object> getEventBaseClass() { return Object.class; }
        @Override protected void registerEventListener(Class<Object> type, Consumer<Object> listener) {
            listeners.put(type, listener);
        }

        void subscribe(Class<?> type) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!isIndexReady()) Thread.sleep(10);
            });
            assertTrue(handleCommand(new String[]{"on", type.getName()}).startsWith("Enabled tap"));
        }
    }

    @Test
    void loaderValidationHookCanRejectNonEvents() {
        var manager = new TestManager() {
            @Override protected boolean isEventClass(Class<?> type) { return type == ParentEvent.class; }
        };
        manager.subscribe(ParentEvent.class);
        assertTrue(manager.handleCommand(new String[]{"on", ChildEvent.class.getName()})
                .startsWith("Class is not an Event subtype"));
        assertFalse(manager.listeners.containsKey(ChildEvent.class));
    }

    @Test
    void parentSubscriptionReceivesChildEvent() {
        var manager = new TestManager();
        manager.subscribe(ParentEvent.class);
        manager.listeners.get(ParentEvent.class).accept(new ChildEvent());
        assertTrue(manager.handleCommand(new String[]{"off", ParentEvent.class.getName()})
                .startsWith("No active taps"), "The one-shot parent subscription must be consumed");
    }

    @Test
    void parentCallbackDoesNotConsumeChildSubscription() {
        var manager = new TestManager();
        manager.subscribe(ParentEvent.class);
        manager.subscribe(ChildEvent.class);
        manager.listeners.get(ParentEvent.class).accept(new ChildEvent());
        assertTrue(manager.handleCommand(new String[]{"off", ChildEvent.class.getName()})
                .startsWith("Disabled 1"), "Only the child listener may dispatch its subscription");
    }

    @Test
    void childCallbackDoesNotConsumeParentSubscription() {
        var manager = new TestManager();
        manager.subscribe(ParentEvent.class);
        manager.subscribe(ChildEvent.class);
        manager.listeners.get(ChildEvent.class).accept(new ChildEvent());
        assertTrue(manager.handleCommand(new String[]{"off", ParentEvent.class.getName()})
                .startsWith("Disabled 1"));
        assertTrue(manager.handleCommand(new String[]{"off", ChildEvent.class.getName()})
                .startsWith("No active taps"));
    }

    @Test
    void expiredQuietEventsDoNotOccupySlots() {
        var bucket = new AbstractEventTapManager.SubscriptionBucket();
        bucket.add(new AbstractEventTapManager.Subscription(UUID.randomUUID(), "QuietEvent", "",
                DurationSpec.Mode.TIMED, System.currentTimeMillis() - 1000, 0));
        assertTrue(bucket.getEnabled().isEmpty());
        assertTrue(bucket.isEmpty());
        assertEquals(0, bucket.disableAll());
    }

    @Test
    void unexpiredSubscriptionsRemainEnabled() {
        var bucket = new AbstractEventTapManager.SubscriptionBucket();
        bucket.add(new AbstractEventTapManager.Subscription(UUID.randomUUID(), "ActiveEvent", "",
                DurationSpec.Mode.TIMED, System.currentTimeMillis() + 60000, 0));
        assertEquals(1, bucket.getEnabled().size());
        assertFalse(bucket.isEmpty());
        assertEquals(1, bucket.disableAll());
        assertTrue(bucket.isEmpty());
    }
}
