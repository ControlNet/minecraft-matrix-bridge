package space.controlnet.minecraftmatrixbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractEventTapManager<E> {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");

    private static final ClassValue<ConcurrentHashMap<String, CompletableFuture<IndexBuild>>> EVENT_INDEX_CACHE =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, CompletableFuture<IndexBuild>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };
    private static final ConcurrentHashMap<ListenerKey, ListenerSlot<?>> LISTENER_SLOTS =
            new ConcurrentHashMap<>();

    private static final int DEFAULT_MAX_TAPS = 10;
    private static final long DEFAULT_THROTTLE_MS = 1000;
    private static final int MAX_MESSAGE_LEN = 2048;
    private static final long TICK_EVENT_MIN_THROTTLE_MS = 1000;
    /** Tick events fire 20+ times a second; each tap looks at one at most this often. */
    private static final long TICK_EVALUATION_INTERVAL_MS = 1000;
    private static final long MAX_TICK_TAP_DURATION_MS = 24L * 60 * 60 * 1000;
    private static final ClassValue<Boolean> TICK_EVENT_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return isTickEventName(type.getName());
        }
    };

    private final Object admissionLock = new Object();

    private final BridgeService bridgeService;
    private final AtomicReference<EventIndex> eventIndexRef = new AtomicReference<>(null);
    private final AtomicReference<Throwable> eventIndexFailureRef = new AtomicReference<>(null);
    private final CompletableFuture<IndexBuild> eventIndexFuture;
    private final AtomicBoolean active = new AtomicBoolean(true);

    private final int maxActiveTaps;
    private final long defaultThrottleMs;

    private final ConcurrentHashMap<String, SubscriptionBucket> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Boolean> registeredListeners = new ConcurrentHashMap<>();
    private final Set<ListenerSlot<E>> listenerSlots = ConcurrentHashMap.newKeySet();

    protected AbstractEventTapManager(BridgeService bridgeService, int maxActiveTaps, long defaultThrottleMs) {
        this(bridgeService, maxActiveTaps, defaultThrottleMs, null);
    }

    AbstractEventTapManager(
            BridgeService bridgeService,
            int maxActiveTaps,
            long defaultThrottleMs,
            CompletableFuture<EventIndex> suppliedIndexFuture
    ) {
        this.bridgeService = bridgeService;
        this.maxActiveTaps = maxActiveTaps > 0 ? maxActiveTaps : DEFAULT_MAX_TAPS;
        this.defaultThrottleMs = defaultThrottleMs > 0 ? defaultThrottleMs : DEFAULT_THROTTLE_MS;
        this.eventIndexFuture = suppliedIndexFuture == null
                ? sharedEventIndexFuture(getClass(), getLoaderName())
                : observeIndexFuture(suppliedIndexFuture, getLoaderName());
        this.eventIndexFuture.thenAccept(build -> {
            eventIndexFailureRef.set(build.failure);
            eventIndexRef.set(build.index);
        });
    }

    private static CompletableFuture<IndexBuild> sharedEventIndexFuture(Class<?> managerType, String loaderName) {
        ConcurrentHashMap<String, CompletableFuture<IndexBuild>> cache = EVENT_INDEX_CACHE.get(managerType);
        return cache.computeIfAbsent(loaderName, key -> {
            CompletableFuture<EventIndex> scan = CompletableFuture.supplyAsync(
                    () -> EventIndex.scanClasspath(managerType.getClassLoader(), loaderName));
            CompletableFuture<IndexBuild> observed = observeIndexFuture(scan, loaderName);
            observed.thenAcceptAsync(build -> {
                if (build.failure != null) {
                    cache.remove(key, observed);
                }
            });
            return observed;
        });
    }

    private static CompletableFuture<IndexBuild> observeIndexFuture(
            CompletableFuture<EventIndex> source,
            String loaderName
    ) {
        return source.handle((index, failure) -> {
            if (failure == null) {
                return new IndexBuild(index == null ? EventIndex.empty() : index, null);
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            LOGGER.log(Level.SEVERE, "Event index build failed for " + loaderName, cause);
            return new IndexBuild(EventIndex.empty(), cause);
        });
    }

    protected abstract String getLoaderName();

    protected abstract Class<E> getEventBaseClass();

    protected boolean isEventClass(Class<?> eventClass) {
        return getEventBaseClass().isAssignableFrom(eventClass);
    }

    protected abstract void registerEventListener(Class<E> eventClass, Consumer<E> listener);

    /**
     * Identity of the event bus used to register listeners. Managers created by
     * reload for the same bus share one dispatcher per event class.
     */
    protected Object getListenerRegistryKey() {
        return getClass();
    }

    public boolean isIndexReady() {
        return eventIndexRef.get() != null;
    }

    EventIndex getEventIndex() {
        return eventIndexRef.get();
    }

    public String handleCommand(String[] args) {
        if (!active.get()) {
            return "Event tap manager is no longer active.";
        }
        if (args == null || args.length == 0) {
            return getHelpText();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "on" -> handleOn(args);
            case "off" -> handleOff(args);
            case "list" -> handleList();
            case "search" -> handleSearch(args);
            case "help" -> getHelpText();
            default -> "Unknown event subcommand: " + sub + ". Try: event help";
        };
    }

    private String handleOn(String[] args) {
        if (args.length < 2) {
            return "Usage: event on <eventName> [filter] [duration]";
        }

        String indexFailure = getIndexFailureMessage();
        if (indexFailure != null) {
            return indexFailure;
        }
        EventIndex index = getEventIndex();
        if (index == null) {
            return "Event index is still building. Please try again in a few seconds.";
        }

        String eventInput = args[1];
        String filter = args.length > 2 ? args[2] : "";
        String durationStr = args.length > 3 ? args[3] : "once";

        ResolveResult resolved = index.resolve(eventInput);
        if (resolved instanceof ResolveResult.NotFound) {
            Class<?> directClass = tryLoadClass(eventInput);
            if (directClass == null) {
                return "Event not found: " + eventInput + ". Use a full class name (FQCN) or search with: event search <query>";
            }
            return enableTap(directClass, eventInput, filter, durationStr);
        } else if (resolved instanceof ResolveResult.Ambiguous ambiguous) {
            StringBuilder sb = new StringBuilder("Multiple events match '").append(eventInput).append("'. Please specify the full class name:\n");
            for (String candidate : ambiguous.candidates()) {
                sb.append("  - ").append(candidate).append("\n");
            }
            return sb.toString().trim();
        } else if (resolved instanceof ResolveResult.Success success) {
            Class<?> eventClass = tryLoadClass(success.fqcn());
            if (eventClass == null) {
                return "Failed to load event class: " + success.fqcn();
            }
            return enableTap(eventClass, success.fqcn(), filter, durationStr);
        }
        return "Unexpected resolution result.";
    }

    private String enableTap(Class<?> eventClass, String fqcn, String filter, String durationStr) {
        if (fqcn.startsWith("net.minecraft.client.")) {
            return "Client-only events cannot be tapped on a dedicated server: " + fqcn;
        }

        if (!isEventClass(eventClass)) {
            return "Class is not an Event subtype: " + fqcn;
        }

        if (bridgeService.isMcToMatrixDisabledByConfig()) {
            // Tap output is sent through the MC -> Matrix queue, which is not running.
            return "Event taps cannot deliver anything while MC -> Matrix is disabled (bridge.enableMcToMatrix=false).";
        }

        DurationSpec duration;
        try {
            duration = DurationSpec.parse(durationStr);
        } catch (IllegalArgumentException e) {
            return "Invalid duration: " + durationStr + ". Use: once, permanent, or <N>s|m|h|d";
        }

        boolean isTickEvent = isTickEventName(fqcn);
        long throttleMs = defaultThrottleMs;
        if (isTickEvent && throttleMs < TICK_EVENT_MIN_THROTTLE_MS) {
            throttleMs = TICK_EVENT_MIN_THROTTLE_MS;
        }
        if (isTickEvent && duration.mode == DurationSpec.Mode.PERMANENT) {
            return "Permanent subscriptions are not allowed for tick events due to performance concerns. Use a timed duration instead.";
        }
        if (isTickEvent && duration.mode == DurationSpec.Mode.TIMED && duration.durationMs > MAX_TICK_TAP_DURATION_MS) {
            return "Tick event taps are limited to 24h. Use a shorter duration.";
        }

        String normalizedFilter = FilterUtil.normalize(filter);
        Subscription sub = new Subscription(
                UUID.randomUUID(),
                fqcn,
                normalizedFilter,
                duration.mode,
                duration.calculateExpiresAtMs(System.currentTimeMillis()),
                throttleMs
        );

        // Counting and adding under one lock keeps concurrent Matrix and in-game
        // commands from exceeding maxActiveTaps.
        synchronized (admissionLock) {
            int activeCount = countActiveTaps();
            if (activeCount >= maxActiveTaps) {
                return "Maximum active taps reached (" + maxActiveTaps + "). Disable some taps first with: event off <eventName>";
            }

            try {
                ensureListenerRegistered(eventClass);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.log(Level.WARNING, "Failed to register event listener for " + fqcn, e);
                return "Failed to register event listener for: " + fqcn + ". Check the server log for details.";
            }

            SubscriptionBucket bucket = subscriptions.computeIfAbsent(fqcn, k -> new SubscriptionBucket());
            bucket.add(sub);
        }

        String durationDisplay = duration.toDisplayString();
        String filterDisplay = normalizedFilter == null ? "(none)" : "'" + filter + "'";
        return "Enabled tap on " + eventClass.getSimpleName() + " (filter=" + filterDisplay + ", duration=" + durationDisplay + ", throttle=" + throttleMs + "ms)";
    }

    private String handleOff(String[] args) {
        if (args.length < 2) {
            return "Usage: event off <eventName>";
        }

        EventIndex index = getEventIndex();
        String eventInput = args[1];
        ResolveResult resolved = index != null ? index.resolve(eventInput) : ResolveResult.notFound();

        String fqcn;
        if (resolved instanceof ResolveResult.Success success) {
            fqcn = success.fqcn();
        } else if (resolved instanceof ResolveResult.Ambiguous) {
            fqcn = eventInput;
        } else {
            fqcn = eventInput;
        }

        SubscriptionBucket bucket = subscriptions.get(fqcn);
        if (bucket == null || bucket.isEmpty()) {
            for (Map.Entry<String, SubscriptionBucket> entry : subscriptions.entrySet()) {
                if (entry.getKey().endsWith("." + eventInput) || entry.getKey().endsWith("$" + eventInput)) {
                    bucket = entry.getValue();
                    fqcn = entry.getKey();
                    break;
                }
            }
        }

        if (bucket == null || bucket.isEmpty()) {
            return "No active taps for: " + eventInput;
        }

        int disabled = bucket.disableAll();
        return "Disabled " + disabled + " tap(s) for " + fqcn;
    }

    private String handleList() {
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, SubscriptionBucket> entry : subscriptions.entrySet()) {
            List<Subscription> active = entry.getValue().getEnabled();
            if (active.isEmpty()) continue;
            for (Subscription sub : active) {
                total++;
                String simpleName = entry.getKey().contains(".")
                    ? entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1)
                    : entry.getKey();
                String line = simpleName + ": mode=" + sub.mode + ", matches=" + sub.matchCount.get();
                if (sub.filterLower != null) {
                    line += ", filter='" + sub.filterLower + "'";
                }
                if (sub.mode == DurationSpec.Mode.TIMED) {
                    long remaining = sub.expiresAtMs - System.currentTimeMillis();
                    if (remaining > 0) {
                        line += ", remaining=" + (remaining / 1000) + "s";
                    }
                }
                lines.add(line);
            }
        }

        if (lines.isEmpty()) {
            return "No active event taps.";
        }
        return "Active taps (" + total + "/" + maxActiveTaps + "):\n" + String.join("\n", lines);
    }

    private String handleSearch(String[] args) {
        if (args.length < 2) {
            return "Usage: event search <query>";
        }

        String indexFailure = getIndexFailureMessage();
        if (indexFailure != null) {
            return indexFailure;
        }
        EventIndex index = getEventIndex();
        if (index == null) {
            return "Event index is still building. Please try again in a few seconds.";
        }

        String query = args[1];
        List<String> results = index.search(query, 10);
        if (results.isEmpty()) {
            return "No events found matching: " + query;
        }
        StringBuilder sb = new StringBuilder("Events matching '").append(query).append("':\n");
        for (String fqcn : results) {
            sb.append("  - ").append(fqcn).append("\n");
        }
        return sb.toString().trim();
    }

    private String getHelpText() {
        return """
            Event tap commands:
              event on <eventName> [filter] [duration] - Enable a tap
              event off <eventName> - Disable taps for an event
              event list - Show active taps
              event search <query> - Search available events
              event help - Show this help
            
            Duration: once (default), permanent, or <N>s|m|h|d (e.g., 30s, 10m, 2h, 1d)
            Filter: Optional substring match (case-insensitive)""";
    }

    private int countActiveTaps() {
        int count = 0;
        for (SubscriptionBucket bucket : subscriptions.values()) {
            count += bucket.getEnabled().size();
        }
        return count;
    }

    private String getIndexFailureMessage() {
        Throwable failure = eventIndexFailureRef.get();
        if (failure == null) {
            return null;
        }
        return "Event index failed to build. Check the server log for details.";
    }

    /**
     * Deactivates this manager and releases it from shared event dispatchers.
     */
    public void close() {
        if (!active.getAndSet(false)) {
            return;
        }
        for (ListenerSlot<E> slot : listenerSlots) {
            slot.deactivate(this);
        }
        listenerSlots.clear();
        subscriptions.clear();
        registeredListeners.clear();
    }

    @SuppressWarnings("unchecked")
    private void ensureListenerRegistered(Class<?> eventClass) {
        if (!active.get() || registeredListeners.putIfAbsent(eventClass, true) != null) {
            return;
        }

        try {
            ListenerKey key = new ListenerKey(getListenerRegistryKey(), eventClass);
            ListenerSlot<E> slot = (ListenerSlot<E>) LISTENER_SLOTS.computeIfAbsent(key, ignored -> {
                ListenerSlot<E> created = new ListenerSlot<>(eventClass.getName());
                registerEventListener((Class<E>) eventClass, created::dispatch);
                LOGGER.fine("Registered shared event listener for: " + eventClass.getName());
                return created;
            });
            slot.activate(this);
            listenerSlots.add(slot);
        } catch (RuntimeException | Error e) {
            registeredListeners.remove(eventClass);
            throw e;
        }
    }

    private void onEvent(String fqcn, E event) {
        if (!active.get()) {
            return;
        }
        SubscriptionBucket bucket = subscriptions.get(fqcn);
        if (bucket == null) {
            return;
        }

        List<Subscription> enabled = bucket.getEnabled();
        if (enabled.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        boolean tickEvent = event != null && TICK_EVENT_TYPES.get(event.getClass());
        String serialized = null;

        for (Subscription sub : enabled) {
            // A permanent tap on a supertype (e.g. the Event base) would otherwise stream
            // every tick event forever; only timed or one-shot taps see ticks.
            if (tickEvent && sub.mode == DurationSpec.Mode.PERMANENT) {
                continue;
            }
            if (!sub.shouldForward(nowMs)) {
                continue;
            }
            // Sample tick events before serializing, so a filter that never matches
            // does not serialize every tick.
            if (tickEvent && !sub.tryClaimEvaluation(nowMs, TICK_EVALUATION_INTERVAL_MS)) {
                continue;
            }

            if (serialized == null) {
                serialized = EventSerializationUtil.serialize(event, MAX_MESSAGE_LEN);
            }

            if (sub.filterLower != null && !FilterUtil.matches(serialized, sub.filterLower)) {
                continue;
            }

            // Events can fire on several threads at once; only one may claim a one-shot
            // tap or a throttle window.
            if (!sub.tryClaimForward(nowMs)) {
                continue;
            }
            forward(EventSerializationUtil.formatForMatrix(event, serialized, MAX_MESSAGE_LEN));
        }

        bucket.cleanupExpired(nowMs);
    }

    static boolean isTickEventName(String fqcn) {
        return fqcn.toLowerCase(Locale.ROOT).contains("tick");
    }

    /** Sends one tap message; overridden in tests. */
    void forward(String message) {
        bridgeService.enqueueMcMessage(message);
    }

    private Class<?> tryLoadClass(String fqcn) {
        try {
            return Class.forName(fqcn, false, getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            return null;
        }
    }

    private static final class IndexBuild {
        final EventIndex index;
        final Throwable failure;

        IndexBuild(EventIndex index, Throwable failure) {
            this.index = index;
            this.failure = failure;
        }
    }

    private static final class ListenerKey {
        private final Object registry;
        private final Class<?> eventClass;

        ListenerKey(Object registry, Class<?> eventClass) {
            this.registry = registry;
            this.eventClass = eventClass;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ListenerKey key
                    && registry == key.registry
                    && eventClass == key.eventClass;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(registry) + System.identityHashCode(eventClass);
        }
    }

    private static final class ListenerSlot<T> {
        private final String eventClassName;
        private final AtomicReference<AbstractEventTapManager<T>> manager = new AtomicReference<>();

        ListenerSlot(String eventClassName) {
            this.eventClassName = eventClassName;
        }

        void activate(AbstractEventTapManager<T> nextManager) {
            manager.set(nextManager);
        }

        void deactivate(AbstractEventTapManager<T> oldManager) {
            manager.compareAndSet(oldManager, null);
        }

        void dispatch(T event) {
            AbstractEventTapManager<T> current = manager.get();
            if (current != null) {
                current.onEvent(eventClassName, event);
            }
        }
    }

    static final class SubscriptionBucket {
        private final List<Subscription> subs = new ArrayList<>();

        synchronized void add(Subscription sub) {
            subs.add(sub);
        }

        synchronized List<Subscription> getEnabled() {
            cleanupExpired(System.currentTimeMillis());
            List<Subscription> result = new ArrayList<>();
            for (Subscription sub : subs) {
                if (sub.enabled.get()) {
                    result.add(sub);
                }
            }
            return result;
        }

        synchronized int disableAll() {
            cleanupExpired(System.currentTimeMillis());
            int count = 0;
            for (Subscription sub : subs) {
                if (sub.enabled.getAndSet(false)) {
                    count++;
                }
            }
            return count;
        }

        synchronized boolean isEmpty() {
            cleanupExpired(System.currentTimeMillis());
            for (Subscription sub : subs) {
                if (sub.enabled.get()) {
                    return false;
                }
            }
            return true;
        }

        synchronized void cleanupExpired(long nowMs) {
            subs.removeIf(sub -> !sub.enabled.get() || (sub.mode == DurationSpec.Mode.TIMED && nowMs > sub.expiresAtMs));
        }
    }

    static final class Subscription {
        final UUID id;
        final String fqcn;
        final String filterLower;
        final DurationSpec.Mode mode;
        final long expiresAtMs;
        final long minIntervalMs;
        final AtomicLong matchCount = new AtomicLong(0);
        final AtomicLong lastForwardedAtMs = new AtomicLong(0);
        final AtomicLong lastEvaluatedAtMs = new AtomicLong(Long.MIN_VALUE);
        final AtomicBoolean enabled = new AtomicBoolean(true);

        Subscription(UUID id, String fqcn, String filterLower, DurationSpec.Mode mode, long expiresAtMs, long minIntervalMs) {
            this.id = id;
            this.fqcn = fqcn;
            this.filterLower = filterLower;
            this.mode = mode;
            this.expiresAtMs = expiresAtMs;
            this.minIntervalMs = minIntervalMs;
        }

        /** Cheap pre-check; {@link #tryClaimForward} makes the decision atomically. */
        boolean shouldForward(long nowMs) {
            if (!enabled.get()) return false;
            if (mode == DurationSpec.Mode.TIMED && nowMs > expiresAtMs) {
                enabled.set(false);
                return false;
            }
            return nowMs - lastForwardedAtMs.get() >= minIntervalMs;
        }

        /** Claims the right to look at an event, at most once per {@code intervalMs}. */
        boolean tryClaimEvaluation(long nowMs, long intervalMs) {
            long last = lastEvaluatedAtMs.get();
            if (last != Long.MIN_VALUE && nowMs - last < intervalMs) {
                return false;
            }
            return lastEvaluatedAtMs.compareAndSet(last, nowMs);
        }

        /** Atomically claims the throttle window and, for one-shot taps, the single forward. */
        boolean tryClaimForward(long nowMs) {
            while (true) {
                if (!enabled.get()) {
                    return false;
                }
                long last = lastForwardedAtMs.get();
                if (nowMs - last < minIntervalMs) {
                    return false;
                }
                if (lastForwardedAtMs.compareAndSet(last, nowMs)) {
                    break;
                }
            }
            if (mode == DurationSpec.Mode.ONCE && !enabled.compareAndSet(true, false)) {
                return false;
            }
            matchCount.incrementAndGet();
            return true;
        }
    }
}
