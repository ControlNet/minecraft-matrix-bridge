package space.controlnet.minecraftmatrixbridge;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Recently run command lines, matched against the chat events their message arguments fire.
 *
 * <p>On Forge 41 the text of {@code /say}, {@code /me}, {@code /msg} and {@code /teammsg} goes
 * through the chat decorator, which fires {@code ServerChatEvent} later and on another task. The
 * command line itself is known earlier, from {@code CommandEvent}, and ends with that text.</p>
 */
final class CommandEchoes {
    static final long WINDOW_MS = 10_000;
    static final int MAX_COMMANDS = 64;

    private record Entry(String input, long atMs) {
    }

    private final ArrayDeque<Entry> recent = new ArrayDeque<>();

    synchronized void noteCommand(String input, long nowMs) {
        if (input == null || input.isBlank()) {
            return;
        }
        if (recent.size() >= MAX_COMMANDS) {
            recent.removeFirst();
        }
        recent.addLast(new Entry(input, nowMs));
    }

    /** True, once per command, when {@code rawMessage} is the message text of a recent command. */
    synchronized boolean consume(String rawMessage, long nowMs) {
        while (!recent.isEmpty() && nowMs - recent.peekFirst().atMs() > WINDOW_MS) {
            recent.removeFirst();
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String suffix = " " + rawMessage;
        for (Iterator<Entry> it = recent.iterator(); it.hasNext(); ) {
            if (it.next().input().endsWith(suffix)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
