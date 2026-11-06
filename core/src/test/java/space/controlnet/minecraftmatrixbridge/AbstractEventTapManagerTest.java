package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AbstractEventTapManagerTest {
    // Synthetic events and listener registry model parent/subtype bus dispatch.
    public static class ParentEvent {}
    public static class ChildEvent extends ParentEvent {}

    /** Name contains "Tick" like the loaders' tick events; counts serializations. */
    public static class CountingTickEvent extends ParentEvent {
        static final java.util.concurrent.atomic.AtomicInteger SERIALIZED = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String toString() {
            SERIALIZED.incrementAndGet();
            return "tick";
        }
    }

    private static final AtomicBoolean NON_EVENT_INITIALIZED = new AtomicBoolean(false);

    public static class InitializingNonEvent {
        static {
            NON_EVENT_INITIALIZED.set(true);
        }
    }

    private static final class TestBus {
        final Map<Class<?>, Consumer<Object>> listeners = new HashMap<>();
        int registrations;
    }

    private static class TestManager extends AbstractEventTapManager<Object> {
        final TestBus bus;
        final Map<Class<?>, Consumer<Object>> listeners;
        final java.util.List<String> forwarded = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        TestManager() {
            this(new TestBus(), CompletableFuture.completedFuture(EventIndex.empty()));
        }

        TestManager(TestBus bus, CompletableFuture<EventIndex> indexFuture) {
            super(new BridgeService(), 10, 1, indexFuture);
            this.bus = bus;
            this.listeners = bus.listeners;
        }

        @Override protected String getLoaderName() { return "test"; }
        @Override protected Class<Object> getEventBaseClass() { return Object.class; }
        @Override protected Object getListenerRegistryKey() { return bus; }
        @Override protected void registerEventListener(Class<Object> type, Consumer<Object> listener) {
            bus.registrations++;
            bus.listeners.put(type, listener);
        }
        @Override void forward(String message) { forwarded.add(message); }

        void subscribe(Class<?> type) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!isIndexReady()) Thread.sleep(10);
            });
            assertTrue(handleCommand(new String[]{"on", type.getName()}).startsWith("Enabled tap"));
        }
    }

    private static final class CachedIndexManager extends AbstractEventTapManager<Object> {
        CachedIndexManager() {
            super(new BridgeService(), 10, 1);
        }

        @Override protected String getLoaderName() { return "cache-test"; }
        @Override protected Class<Object> getEventBaseClass() { return Object.class; }
        @Override protected void registerEventListener(Class<Object> type, Consumer<Object> listener) {
        }
    }

    @Test
    void reloadManagersShareTheBuiltEventIndex() {
        var first = new CachedIndexManager();
        var second = new CachedIndexManager();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!first.isIndexReady() || !second.isIndexReady()) Thread.sleep(10);
        });
        assertSame(first.getEventIndex(), second.getEventIndex());
    }

    @Test
    void closeReleasesSharedListenerAndReloadReusesRegistration() {
        TestBus bus = new TestBus();
        var first = new TestManager(bus, CompletableFuture.completedFuture(EventIndex.empty()));
        first.subscribe(ParentEvent.class);
        Consumer<Object> registeredListener = bus.listeners.get(ParentEvent.class);

        first.close();
        var second = new TestManager(bus, CompletableFuture.completedFuture(EventIndex.empty()));
        second.subscribe(ParentEvent.class);

        assertEquals(1, bus.registrations, "reload must not register another global listener");
        registeredListener.accept(new ParentEvent());
        assertTrue(second.handleCommand(new String[]{"off", ParentEvent.class.getName()})
                .startsWith("No active taps"), "the shared listener must route to the replacement manager");
    }

    @Test
    void invalidFqcnDoesNotRunStaticInitializer() {
        NON_EVENT_INITIALIZED.set(false);
        var manager = new TestManager() {
            @Override protected boolean isEventClass(Class<?> type) { return type == ParentEvent.class; }
        };

        String result = manager.handleCommand(new String[]{
                "on",
                "space.controlnet.minecraftmatrixbridge.AbstractEventTapManagerTest$InitializingNonEvent"
        });

        assertTrue(result.startsWith("Class is not an Event subtype"));
        assertFalse(NON_EVENT_INITIALIZED.get(), "type validation must not initialize arbitrary classes");
    }

    @Test
    void indexFailureBecomesDiagnosableTerminalState() {
        CompletableFuture<EventIndex> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("synthetic scan failure"));
        var manager = new TestManager(new TestBus(), failed);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            while (!manager.isIndexReady()) Thread.sleep(10);
        });
        assertTrue(manager.handleCommand(new String[]{"search", "Event"})
                .startsWith("Event index failed to build"));
    }

    @Test
    void listenerRegistrationFailureDoesNotEscapeOrLeaveSubscription() {
        var manager = new TestManager() {
            @Override
            protected void registerEventListener(Class<Object> type, Consumer<Object> listener) {
                throw new IllegalStateException("synthetic registration failure");
            }
        };

        assertTrue(manager.handleCommand(new String[]{"on", ParentEvent.class.getName()})
                .startsWith("Failed to register event listener"));
        assertEquals("No active event taps.", manager.handleCommand(new String[]{"list"}));
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

    private static void waitForIndex(TestManager manager) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!manager.isIndexReady()) Thread.sleep(10);
        });
    }

    private static void runConcurrently(int threads, Runnable task) throws InterruptedException {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                task.run();
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5_000);
        }
    }

    @Test
    void onceSubscriptionForwardsExactlyOnceUnderConcurrentDispatch() throws InterruptedException {
        for (int trial = 0; trial < 50; trial++) {
            var manager = new TestManager();
            manager.subscribe(ParentEvent.class);
            Consumer<Object> listener = manager.listeners.get(ParentEvent.class);
            runConcurrently(8, () -> {
                for (int i = 0; i < 50; i++) listener.accept(new ParentEvent());
            });
            assertEquals(1, manager.forwarded.size(), "trial " + trial);
            manager.close();
        }
    }

    @Test
    void concurrentEnableCommandsRespectTheTapLimit() throws InterruptedException {
        var manager = new TestManager();
        waitForIndex(manager);
        java.util.concurrent.atomic.AtomicInteger enabled = new java.util.concurrent.atomic.AtomicInteger();
        runConcurrently(32, () -> {
            String reply = manager.handleCommand(new String[]{"on", ParentEvent.class.getName(), "", "10m"});
            if (reply.startsWith("Enabled tap")) enabled.incrementAndGet();
        });
        assertEquals(10, enabled.get());
    }

    @Test
    void tickEventsAreEvaluatedAtMostOncePerSecondEvenWhenTheFilterNeverMatches() {
        var manager = new TestManager();
        waitForIndex(manager);
        assertTrue(manager.handleCommand(new String[]{"on", CountingTickEvent.class.getName(), "never-matches", "10m"})
                .startsWith("Enabled tap"));
        CountingTickEvent.SERIALIZED.set(0);
        Consumer<Object> listener = manager.listeners.get(CountingTickEvent.class);
        for (int i = 0; i < 200; i++) listener.accept(new CountingTickEvent());
        assertEquals(1, CountingTickEvent.SERIALIZED.get());
        assertTrue(manager.forwarded.isEmpty());
    }

    @Test
    void permanentSupertypeTapsSkipTickEvents() {
        var manager = new TestManager();
        waitForIndex(manager);
        assertTrue(manager.handleCommand(new String[]{"on", ParentEvent.class.getName(), "", "permanent"})
                .startsWith("Enabled tap"));
        Consumer<Object> listener = manager.listeners.get(ParentEvent.class);
        listener.accept(new CountingTickEvent());
        assertTrue(manager.forwarded.isEmpty(), "a permanent tap must not stream tick events");
        listener.accept(new ChildEvent());
        assertEquals(1, manager.forwarded.size());
    }

    @Test
    void timedTickTapsAreCappedAtOneDay() {
        var manager = new TestManager();
        waitForIndex(manager);
        String name = CountingTickEvent.class.getName();
        assertTrue(manager.handleCommand(new String[]{"on", name, "", "2d"}).contains("24h"));
        assertTrue(manager.handleCommand(new String[]{"on", name, "", "24h"}).startsWith("Enabled tap"));
    }
}
