package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.Component;

/**
 * Reads chat the way Forge 1.18 broadcasts it. {@code ServerChatEvent.getMessage()} is the text as
 * typed; mods that format, filter or hide chat change {@code getComponent()} instead, and Forge
 * broadcasts that component (or nothing when it is null).
 */
final class ChatComponents {
    private ChatComponents() {
    }

    /** The chat line as players see it ("&lt;Steve&gt; hi" in vanilla), or null when nothing is shown. */
    static String shownLine(Component component) {
        if (component == null) {
            return null;
        }
        String line = component.getString();
        return line.isBlank() ? null : line;
    }
}
