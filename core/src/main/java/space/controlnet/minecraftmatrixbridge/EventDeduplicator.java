package space.controlnet.minecraftmatrixbridge;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EventDeduplicator {
    private final int maxSize;
    private final LinkedHashMap<String, Boolean> lru;

    public EventDeduplicator(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.lru = new LinkedHashMap<>(this.maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > EventDeduplicator.this.maxSize;
            }
        };
    }

    public synchronized boolean seenOrAdd(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true;
        }
        if (lru.containsKey(eventId)) {
            return true;
        }
        lru.put(eventId, Boolean.TRUE);
        return false;
    }
}
