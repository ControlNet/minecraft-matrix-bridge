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
}
