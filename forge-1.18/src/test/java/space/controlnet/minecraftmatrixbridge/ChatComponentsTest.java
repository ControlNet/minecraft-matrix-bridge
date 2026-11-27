package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Components built like vanilla 1.18.2 and like chat-formatting mods do; no server is started. */
class ChatComponentsTest {
    @Test
    void vanillaChatKeepsTheBridgeFormat() {
        // ServerGamePacketListenerImpl builds chat.type.text ("<%s> %s") with the raw string.
        assertEquals("<Steve> hi", ChatComponents.shownLine(
                new TranslatableComponent("chat.type.text", new TextComponent("Steve"), "hi")));
    }

    @Test
    void keepsWhatAModAddsToTheLine() {
        var line = new TranslatableComponent("chat.type.text", new TextComponent("[VIP] Probe"), new TextComponent("***"));
        line.append(new TextComponent(" [suffix]"));
        assertEquals("<[VIP] Probe> *** [suffix]", ChatComponents.shownLine(line));
        assertEquals("[Admin] Steve: hi", ChatComponents.shownLine(new TextComponent("[Admin] Steve: hi")));
    }

    @Test
    void nothingIsRelayedWhenAModHidesTheMessage() {
        assertNull(ChatComponents.shownLine(null));
    }
}
