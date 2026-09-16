package space.controlnet.minecraftmatrixbridge;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Workaround for NightConfig's pre-3.8.3 non-daemon FileWatcher executor. */
public final class ConfigWatcherShutdown {
    private ConfigWatcherShutdown() {}

    public static boolean isAffected(String version) {
        return version != null && (version.matches("3\\.7\\.\\d+")
                || version.matches("3\\.8\\.[012]"));
    }

    public static Thread afterServerExit(boolean dedicated, String version, Thread serverThread, Runnable stopWatcher) {
        if (!dedicated || !isAffected(version)) return null;
        Thread cleanup = new Thread(() -> {
            try {
                // Forge unloads server configs after ServerStoppedEvent. Do not
                // close the shared watcher until that thread has fully exited.
                serverThread.join();
                stopWatcher.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Logger.getLogger("MatrixBridge").log(Level.WARNING, "Could not stop the config watcher", e);
            }
        }, "MatrixBridge-ConfigWatcherStopper");
        cleanup.start();
        return cleanup;
    }
}
