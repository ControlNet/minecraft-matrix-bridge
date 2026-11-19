package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Reads chat the way Forge 1.18 broadcasts it. {@code ServerChatEvent.getMessage()} is the text as
 * typed; mods that filter or hide chat change {@code getComponent()} instead, and Forge broadcasts
 * that component (or nothing when it is null).
 */
final class ChatComponents {
    private ChatComponents() {
    }

    /**
     * The message part of vanilla's {@code chat.type.text} ("&lt;name&gt; text") component, or null
     * when a mod replaced the whole format.
     */
    static String vanillaChatBody(Component component) {
        if (component instanceof TranslatableComponent translatable
                && "chat.type.text".equals(translatable.getKey())
                && translatable.getArgs().length == 2) {
            Object body = translatable.getArgs()[1];
            if (body instanceof Component text) {
                return text.getString();
            }
            if (body instanceof String text) {
                return text;
            }
        }
        return null;
    }
}
