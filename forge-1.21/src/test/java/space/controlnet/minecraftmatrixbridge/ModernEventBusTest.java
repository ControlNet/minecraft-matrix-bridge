package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic event/bus fixtures, not Minecraft events or a replacement event engine. */
class ModernEventBusTest {
    private final ModernEventBus adapter = new ModernEventBus(Event.class, Bus.class, (byte) 3);

    @BeforeEach
    void clearListeners() {
        Parent.BUS.listeners.clear();
        Child.BUS.listeners.clear();
        Pre.BUS.listeners.clear();
        Post.BUS.listeners.clear();
        Chat.BUS.listeners.clear();
    }

    @Test
    void preservesTickFamilyAliasesWithoutChangingOtherNames() {
        for (String alias : List.of("ServerTickEvent", "TickEvent.ServerTickEvent", "TickEvent$ServerTickEvent")) {
            assertEquals("net.minecraftforge.event.TickEvent$ServerTickEvent", ModernEventBus.eventName(alias));
        }
        String fqcn = "net.minecraftforge.event.TickEvent$ServerTickEvent";
        assertEquals(fqcn, ModernEventBus.eventName(fqcn));
        assertEquals("ServerChatEvent", ModernEventBus.eventName("ServerChatEvent"));
    }

    @Test
    void subscribesOnlyToTheDeclaredChildBus() {
        List<Object> received = new ArrayList<>();
        adapter.listen(Child.class, received::add);
        assertTrue(Parent.BUS.listeners.isEmpty());
        Child event = new Child();
        Child.BUS.fire(event);
        assertEquals(List.of(event), received);
        assertEquals(3, Child.BUS.priority);
    }

    @Test
    void parentWithOwnBusDoesNotRegisterChildrenTwice() {
        adapter.listen(Parent.class, ignored -> {});
        assertEquals(1, Parent.BUS.listeners.size());
        assertTrue(Child.BUS.listeners.isEmpty());
    }

    @Test
    void sealedEventFamilySubscribesToBothPhases() {
        List<Object> received = new ArrayList<>();
        assertTrue(adapter.isEventClass(Tick.class));
        adapter.listen(Tick.class, received::add);
        Pre pre = new Pre();
        Post post = new Post();
        Pre.BUS.fire(pre);
        Post.BUS.fire(post);
        assertEquals(List.of(pre, post), received);
    }

    @Test
    void neverUsesTheAlwaysCancellingOverload() {
        List<Object> received = new ArrayList<>();
        adapter.listen(Chat.class, received::add);
        assertEquals(3, Chat.BUS.priority);
        Chat event = new Chat();
        Chat.BUS.fire(event);
        assertEquals(List.of(event), received);
    }

    @Test
    void unrelatedClassesAndMissingOwnBusesAreRejected() {
        assertFalse(adapter.isEventClass(String.class));
        assertThrows(IllegalArgumentException.class, () -> adapter.listen(String.class, ignored -> {}));
        assertThrows(IllegalStateException.class, () -> adapter.listen(MissingBus.class, ignored -> {}));
        assertTrue(Parent.BUS.listeners.isEmpty());
    }

    @Test
    void malformedBusIsRejectedBeforeRegistration() {
        assertThrows(IllegalStateException.class, () -> adapter.listen(BadBus.class, ignored -> {}));
    }

    public interface Event {}
    public static class Bus {
        final List<Consumer<Object>> listeners = new ArrayList<>();
        byte priority;
        public void addListener(byte priority, Consumer<Object> listener) {
            this.priority = priority;
            listeners.add(listener);
        }
        void fire(Object event) {
            listeners.forEach(listener -> listener.accept(event));
        }
    }
    public static final class CancelBus extends Bus {
        public void addListener(byte priority, boolean alwaysCancelling, Consumer<Object> listener) {
            throw new AssertionError("Must not use the cancelling overload");
        }
    }
    public static class Parent implements Event { public static final Bus BUS = new Bus(); }
    public static final class Child extends Parent { public static final Bus BUS = new Bus(); }
    public static final class MissingBus extends Parent {}
    public sealed interface Tick permits Pre, Post {}
    public static final class Pre implements Tick, Event { public static final Bus BUS = new Bus(); }
    public static final class Post implements Tick, Event { public static final Bus BUS = new Bus(); }
    public static final class Chat implements Event { public static final CancelBus BUS = new CancelBus(); }
    public static final class BadBus implements Event { public static final String BUS = "not a bus"; }
}
