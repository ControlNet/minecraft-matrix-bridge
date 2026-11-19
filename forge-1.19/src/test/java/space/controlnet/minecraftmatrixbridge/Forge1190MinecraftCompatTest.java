package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Forge1190MinecraftCompatTest {
    @Test
    void selectsOnlyMinecraft1190() {
        assertTrue(Forge1190MinecraftCompat.supports("1.19"));
        for (String minecraft : new String[]{"1.19.1", "1.19.2", "1.19.4", "1.20", "", "1.19.0"}) {
            assertFalse(Forge1190MinecraftCompat.supports(minecraft), minecraft);
        }
    }

    @Test
    void forge41StringMessageIsReturnedVerbatim() {
        assertEquals("hello", Forge1190MinecraftCompat.chatText("hello"));
        assertEquals("", Forge1190MinecraftCompat.chatText(null));
        assertEquals("***", Forge1190MinecraftCompat.chatText(Component.literal("***")));
    }

    @Test
    void resolvesTheSrgMembersOnTheTargetShape() {
        // Synthetic stand-ins with the 1.19.0 SRG member names; not the real classes.
        assertDoesNotThrow(() -> new Forge1190MinecraftCompat(SyntheticPlayerList.class, SyntheticChatType.class,
                SyntheticChatEvent.class));
    }

    @Test
    void missingSrgMemberFailsAtSelectionInsteadOfOnFirstMessage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new Forge1190MinecraftCompat(Object.class, SyntheticChatType.class, SyntheticChatEvent.class));
        assertTrue(error.getMessage().contains("Minecraft 1.19"));
    }

    public static final class SyntheticPlayerList {
        public void m_215627_(Component message, ResourceKey<?> type) {
        }
    }

    public static final class SyntheticChatType {
        public static final ResourceKey<?> f_130599_ = null;
    }

    /** Forge 41 shape: getMessage() is the raw text, getComponent() what is broadcast. */
    public static final class SyntheticChatEvent {
        public String getMessage() {
            return "hello";
        }

        public Component getComponent() {
            return null;
        }
    }

    public static final class SyntheticRawOnlyChatEvent {
        public String getMessage() {
            return "hello";
        }
    }

    @Test
    void readsTheBroadcastComponentNotTheRawText() {
        // Without getComponent() the adapter would relay text that mods filtered or removed in game.
        assertThrows(IllegalStateException.class,
                () -> new Forge1190MinecraftCompat(SyntheticPlayerList.class, SyntheticChatType.class,
                        SyntheticRawOnlyChatEvent.class));
    }
}
