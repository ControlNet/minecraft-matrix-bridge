package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConfigWatcherShutdownTest {
    @Test
    void onlyKnownAffectedVersionsNeedCleanup() {
        for (String version : new String[]{"3.7.0", "3.7.3", "3.7.4", "3.8.0", "3.8.1", "3.8.2"}) {
            assertTrue(ConfigWatcherShutdown.isAffected(version), version);
        }
        for (String version : new String[]{"", "3.6.4", "3.8.3", "3.9.0", "4.0.0"}) {
            assertFalse(ConfigWatcherShutdown.isAffected(version), version);
        }
        assertFalse(ConfigWatcherShutdown.isAffected(null));
    }

    @Test
    void integratedServersAndUnaffectedVersionsAreUntouched() {
        Runnable unexpected = () -> fail("Watcher must remain available");
        assertNull(ConfigWatcherShutdown.afterServerExit(false, "3.7.3", Thread.currentThread(), unexpected));
        assertNull(ConfigWatcherShutdown.afterServerExit(true, "3.8.3", Thread.currentThread(), unexpected));
    }

    @Test
    void cleanupWaitsForServerConfigUnload() throws Exception {
        // Synthetic server thread models the ordering of Forge's shutdown stages.
        var releaseServer = new CountDownLatch(1);
        var watcherStopped = new CountDownLatch(1);
        Thread server = new Thread(() -> {
            try { releaseServer.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        server.start();
        Thread cleanup = ConfigWatcherShutdown.afterServerExit(true, "3.7.3", server, watcherStopped::countDown);
        try {
            assertFalse(watcherStopped.await(50, TimeUnit.MILLISECONDS));
            releaseServer.countDown();
            assertTrue(watcherStopped.await(5, TimeUnit.SECONDS));
        } finally {
            releaseServer.countDown();
            server.join(5000);
            cleanup.join(5000);
        }
        assertFalse(cleanup.isAlive());
    }
}
