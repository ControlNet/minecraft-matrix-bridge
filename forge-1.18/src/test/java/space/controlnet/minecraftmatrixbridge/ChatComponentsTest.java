package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Components built like vanilla 1.18.2 and like chat-filter mods do; no server is started. */
class ChatComponentsTest {
    @Test
    void readsTheMessageOfVanillaChat() {
        // ServerGamePacketListenerImpl passes the raw string as the second argument.
        assertEquals("hi", ChatComponents.vanillaChatBody(
                new TranslatableComponent("chat.type.text", new TextComponent("Steve"), "hi")));
    }

    @Test
    void readsTheTextAModPutInPlaceOfTheMessage() {
        assertEquals("***", ChatComponents.vanillaChatBody(
                new TranslatableComponent("chat.type.text", new TextComponent("Steve"), new TextComponent("***"))));
    }

    @Test
    void reportsAReplacedFormat() {
        assertNull(ChatComponents.vanillaChatBody(new TextComponent("[Admin] Steve: hi")));
        assertNull(ChatComponents.vanillaChatBody(
                new TranslatableComponent("chat.type.announcement", new TextComponent("Steve"), "hi")));
    }
}
