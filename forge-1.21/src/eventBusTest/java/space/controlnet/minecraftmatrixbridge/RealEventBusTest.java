package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;
import net.minecraftforge.eventbus.api.event.MutableEvent;
import net.minecraftforge.eventbus.api.event.characteristic.Cancellable;
import net.minecraftforge.eventbus.api.listener.Priority;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic events posted through the real EventBus 7 library, not a mocked bus. */
class RealEventBusTest {
    private final ModernEventBus adapter = ModernEventBus.load(getClass().getClassLoader());

    @Test
    void normalListenerNeitherCancelsNorReceivesCancelledEvents() {
        List<Object> received = new ArrayList<>();
        adapter.listen(Chat.class, received::add);
        Chat first = new Chat();
        assertFalse(Chat.BUS.post(first));
        assertEquals(List.of(first), received);
        var canceller = Chat.BUS.addListener(Priority.HIGHEST, (Predicate<Chat>) ignored -> true);
        assertTrue(Chat.BUS.post(new Chat()));
        assertEquals(List.of(first), received);
        Chat.BUS.removeListener(canceller);
    }

    @Test
    void sealedParentReceivesEachRecordExactlyOnce() {
        List<Object> received = new ArrayList<>();
        adapter.listen(Tick.class, received::add);
        Pre pre = new Pre();
        Post post = new Post();
        Pre.BUS.post(pre);
        Post.BUS.post(post);
        assertEquals(List.of(pre, post), received);
    }

    public static final class Chat extends MutableEvent implements Cancellable {
        public static final CancellableEventBus<Chat> BUS = CancellableEventBus.create(Chat.class);
    }
    public sealed interface Tick permits Pre, Post {}
    public record Pre() implements RecordEvent, Tick {
        public static final EventBus<Pre> BUS = EventBus.create(Pre.class);
    }
    public record Post() implements RecordEvent, Tick {
        public static final EventBus<Post> BUS = EventBus.create(Post.class);
    }
}
