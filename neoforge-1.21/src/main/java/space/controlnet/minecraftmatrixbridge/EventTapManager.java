package space.controlnet.minecraftmatrixbridge;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class EventTapManager {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");

    private static final int DEFAULT_MAX_TAPS = 10;
    private static final long DEFAULT_THROTTLE_MS = 1000;
    private static final int MAX_MESSAGE_LEN = 2048;
    private static final long TICK_EVENT_MIN_THROTTLE_MS = 1000;

    private final BridgeService bridgeService;
    private final IEventBus eventBus;
    private final AtomicReference<EventIndex> eventIndexRef = new AtomicReference<>(null);
    private final CompletableFuture<EventIndex> eventIndexFuture;

    private final int maxActiveTaps;
    private final long defaultThrottleMs;

    private final ConcurrentHashMap<String, SubscriptionBucket> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Boolean> registeredListeners = new ConcurrentHashMap<>();

    public EventTapManager(BridgeService bridgeService, IEventBus eventBus, int maxActiveTaps, long defaultThrottleMs) {
        this.bridgeService = bridgeService;
        this.eventBus = eventBus;
        this.maxActiveTaps = maxActiveTaps > 0 ? maxActiveTaps : DEFAULT_MAX_TAPS;
        this.defaultThrottleMs = defaultThrottleMs > 0 ? defaultThrottleMs : DEFAULT_THROTTLE_MS;
        this.eventIndexFuture = CompletableFuture.supplyAsync(() -> {
            EventIndex index = EventIndex.scanClasspath(EventTapManager.class.getClassLoader(), "neoforge-1.21");
            eventIndexRef.set(index);
            return index;
        });
    }

    public boolean isIndexReady() {
        return eventIndexRef.get() != null;
    }

    private EventIndex getEventIndex() {
        return eventIndexRef.get();
    }

    public String handleCommand(String[] args) {
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

        if (!Event.class.isAssignableFrom(eventClass)) {
            return "Class is not an Event subtype: " + fqcn;
        }

        int activeCount = countActiveTaps();
        if (activeCount >= maxActiveTaps) {
            return "Maximum active taps reached (" + maxActiveTaps + "). Disable some taps first with: event off <eventName>";
        }

        DurationSpec duration;
        try {
            duration = DurationSpec.parse(durationStr);
        } catch (IllegalArgumentException e) {
            return "Invalid duration: " + durationStr + ". Use: once, permanent, or <N>s|m|h|d";
        }

        boolean isTickEvent = fqcn.toLowerCase(Locale.ROOT).contains("tick");
        long throttleMs = defaultThrottleMs;
        if (isTickEvent && throttleMs < TICK_EVENT_MIN_THROTTLE_MS) {
            throttleMs = TICK_EVENT_MIN_THROTTLE_MS;
        }
        if (isTickEvent && duration.mode == DurationSpec.Mode.PERMANENT) {
            return "Permanent subscriptions are not allowed for tick events due to performance concerns. Use a timed duration instead.";
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

        SubscriptionBucket bucket = subscriptions.computeIfAbsent(fqcn, k -> new SubscriptionBucket());
        bucket.add(sub);

        ensureListenerRegistered(eventClass);

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

    @SuppressWarnings("unchecked")
    private void ensureListenerRegistered(Class<?> eventClass) {
        if (registeredListeners.containsKey(eventClass)) {
            return;
        }

        registeredListeners.put(eventClass, true);

        Consumer<Event> listener = this::onEvent;
        eventBus.addListener(EventPriority.NORMAL, false, (Class<Event>) eventClass, listener);
        LOGGER.fine("Registered event listener for: " + eventClass.getName());
    }

    private void onEvent(Event event) {
        String fqcn = event.getClass().getName();
        SubscriptionBucket bucket = subscriptions.get(fqcn);
        if (bucket == null) {
            return;
        }

        List<Subscription> enabled = bucket.getEnabled();
        if (enabled.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        String serialized = null;

        for (Subscription sub : enabled) {
            if (!sub.shouldForward(nowMs)) {
                continue;
            }

            if (serialized == null) {
                serialized = EventSerializationUtil.serialize(event, MAX_MESSAGE_LEN);
            }

            if (sub.filterLower != null && !FilterUtil.matches(serialized, sub.filterLower)) {
                continue;
            }

            String msg = EventSerializationUtil.formatForMatrix(event, MAX_MESSAGE_LEN);
            bridgeService.enqueueMcMessage(msg);
            sub.recordMatch(nowMs);
        }

        bucket.cleanupExpired(nowMs);
    }

    private static Class<?> tryLoadClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        }
    }

    static final class SubscriptionBucket {
        private final List<Subscription> subs = new ArrayList<>();

        synchronized void add(Subscription sub) {
            subs.add(sub);
        }

        synchronized List<Subscription> getEnabled() {
            List<Subscription> result = new ArrayList<>();
            for (Subscription sub : subs) {
                if (sub.enabled) {
                    result.add(sub);
                }
            }
            return result;
        }

        synchronized int disableAll() {
            int count = 0;
            for (Subscription sub : subs) {
                if (sub.enabled) {
                    sub.enabled = false;
                    count++;
                }
            }
            return count;
        }

        synchronized boolean isEmpty() {
            for (Subscription sub : subs) {
                if (sub.enabled) {
                    return false;
                }
            }
            return true;
        }

        synchronized void cleanupExpired(long nowMs) {
            subs.removeIf(sub -> !sub.enabled || (sub.mode == DurationSpec.Mode.TIMED && nowMs > sub.expiresAtMs));
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
        volatile boolean enabled = true;

        Subscription(UUID id, String fqcn, String filterLower, DurationSpec.Mode mode, long expiresAtMs, long minIntervalMs) {
            this.id = id;
            this.fqcn = fqcn;
            this.filterLower = filterLower;
            this.mode = mode;
            this.expiresAtMs = expiresAtMs;
            this.minIntervalMs = minIntervalMs;
        }

        boolean shouldForward(long nowMs) {
            if (!enabled) return false;
            if (mode == DurationSpec.Mode.TIMED && nowMs > expiresAtMs) {
                enabled = false;
                return false;
            }
            if (nowMs - lastForwardedAtMs.get() < minIntervalMs) {
                return false;
            }
            return true;
        }

        void recordMatch(long nowMs) {
            matchCount.incrementAndGet();
            lastForwardedAtMs.set(nowMs);
            if (mode == DurationSpec.Mode.ONCE) {
                enabled = false;
            }
        }
    }
}
