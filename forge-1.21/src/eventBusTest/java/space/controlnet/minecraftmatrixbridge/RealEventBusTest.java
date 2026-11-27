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
    void monitorRunsWhenItIsTheOnlyListener() {
        List<String> received = new ArrayList<>();
        adapter.monitor(Lonely.class, event -> received.add(((Lonely) event).message));
        Lonely chat = new Lonely();
        chat.message = "hello";
        assertFalse(Lonely.BUS.post(chat));
        assertEquals(List.of("hello"), received);
    }

    @Test
    void monitorSeesTheFinalChatAndSkipsCancelledChat() {
        List<String> received = new ArrayList<>();
        // Registered first, as the bridge is when a formatter mod loads after it.
        adapter.monitor(Chat.class, event -> received.add(((Chat) event).message));
        var formatter = Chat.BUS.addListener(Priority.LOWEST, (java.util.function.Consumer<Chat>) chat ->
                chat.message = "[Town] " + chat.message);
        Chat chat = new Chat();
        chat.message = "good morning";
        assertFalse(Chat.BUS.post(chat));
        assertEquals(List.of("[Town] good morning"), received);
        // A mute mod cancelling at any priority, even the lowest, hides the message.
        for (byte priority : new byte[] {Priority.LOW, Priority.LOWEST}) {
            var muter = Chat.BUS.addListener(priority, (Predicate<Chat>) ignored -> true);
            assertTrue(Chat.BUS.post(new Chat()));
            Chat.BUS.removeListener(muter);
        }
        assertEquals(List.of("[Town] good morning"), received);
        Chat.BUS.removeListener(formatter);
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
        String message = "";
    }
    public static final class Lonely extends MutableEvent implements Cancellable {
        public static final CancellableEventBus<Lonely> BUS = CancellableEventBus.create(Lonely.class);
        String message = "";
    }
    public sealed interface Tick permits Pre, Post {}
    public record Pre() implements RecordEvent, Tick {
        public static final EventBus<Pre> BUS = EventBus.create(Pre.class);
    }
    public record Post() implements RecordEvent, Tick {
        public static final EventBus<Post> BUS = EventBus.create(Post.class);
    }
}
