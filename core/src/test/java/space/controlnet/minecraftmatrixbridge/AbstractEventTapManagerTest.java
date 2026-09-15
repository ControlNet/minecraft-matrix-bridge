package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AbstractEventTapManagerTest {
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
