package space.controlnet.minecraftmatrixbridge;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Loader-specific callbacks into the Minecraft server environment.
 *
 * <p>Implementations must be safe to call from background threads and must ensure any actual Minecraft API usage
 * happens on the main server thread (e.g., via {@code server.execute(...)}).</p>
 */
public interface McCallbacks {
    void broadcast(String text);

    /**
     * Announce that the Matrix room is connected.
     *
     * <p>Default implementation broadcasts an English message. Loader glue may override this to provide
     * per-player localization.</p>
     */
    default void announceMatrixConnected(String roomIdOrAlias) {
        String room = (roomIdOrAlias == null || roomIdOrAlias.isBlank()) ? "<unknown>" : roomIdOrAlias;
        broadcast("Matrix room connected: " + room);
    }

    /**
     * Get the current online player names.
     *
     * <p>Implementations must be safe to call from background threads, and should schedule any server access onto
     * the main thread and complete the returned future once done.</p>
     */
    default CompletableFuture<List<String>> getOnlinePlayerNames() {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Handles an event tap command from Matrix.
     *
     * <p>This callback is invoked when a Matrix message starts with the bot prefix followed by "event".
     * The loader glue implementation should delegate to the EventTapManager.
     *
     * <p>Implementations must be safe to call from background threads. If any Minecraft API access is needed,
     * it should be scheduled onto the main server thread.
     *
     * @param senderMxid the Matrix user ID of the sender
     * @param args       the command arguments (e.g., ["on", "ServerChatEvent", "filter", "30s"])
     * @return a future that completes with the response text to send back to Matrix
     */
    default CompletableFuture<String> handleEventTapCommand(String senderMxid, String[] args) {
        return CompletableFuture.completedFuture("Event tap commands are not supported in this version.");
    }
}
