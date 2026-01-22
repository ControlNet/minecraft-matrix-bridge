package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EventDeduplicatorTest {

    @Test
    void firstSeeReturnsFalseSubsequentReturnsTrue() {
        EventDeduplicator dedup = new EventDeduplicator(10);

        assertFalse(dedup.seenOrAdd("$event1"));
        assertTrue(dedup.seenOrAdd("$event1"));
        assertTrue(dedup.seenOrAdd("$event1"));
    }

    @Test
    void differentEventsAreNotDuplicates() {
        EventDeduplicator dedup = new EventDeduplicator(10);

        assertFalse(dedup.seenOrAdd("$event1"));
        assertFalse(dedup.seenOrAdd("$event2"));
        assertFalse(dedup.seenOrAdd("$event3"));

        assertTrue(dedup.seenOrAdd("$event1"));
        assertTrue(dedup.seenOrAdd("$event2"));
        assertTrue(dedup.seenOrAdd("$event3"));
    }

    @Test
    void nullOrBlankEventIdTreatedAsSeen() {
        EventDeduplicator dedup = new EventDeduplicator(10);

        assertTrue(dedup.seenOrAdd(null));
        assertTrue(dedup.seenOrAdd(""));
        assertTrue(dedup.seenOrAdd("   "));
    }

    @Test
    void evictsOldestEntriesWhenFull() {
        EventDeduplicator dedup = new EventDeduplicator(3);

        assertFalse(dedup.seenOrAdd("$e1"));
        assertFalse(dedup.seenOrAdd("$e2"));
        assertFalse(dedup.seenOrAdd("$e3"));

        assertFalse(dedup.seenOrAdd("$e4"));

        assertTrue(dedup.seenOrAdd("$e2"), "$e2 should still be in cache");
        assertTrue(dedup.seenOrAdd("$e3"), "$e3 should still be in cache");
        assertTrue(dedup.seenOrAdd("$e4"), "$e4 should still be in cache");
        assertFalse(dedup.seenOrAdd("$e1"), "$e1 should have been evicted when $e4 was added");
    }

    @Test
    void evictedEntryCanBeReadded() {
        EventDeduplicator dedup = new EventDeduplicator(3);

        assertFalse(dedup.seenOrAdd("$e1"));
        assertFalse(dedup.seenOrAdd("$e2"));
        assertFalse(dedup.seenOrAdd("$e3"));
        assertFalse(dedup.seenOrAdd("$e4"));
        assertFalse(dedup.seenOrAdd("$e1"), "$e1 was evicted and is re-added");
        assertTrue(dedup.seenOrAdd("$e1"), "$e1 is now in cache again");
    }
}
