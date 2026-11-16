package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeService {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");

    /**
     * Lines queued while a send is in flight go out together, so a burst of chat or tap
     * output costs one Matrix event instead of one each (homeservers rate-limit messages).
     */
    static final int MAX_BATCH_LINES = 20;
    private static final int MAX_BATCH_CHARS = 16_000;
    /** Longer Matrix messages are cut so one remote user cannot flood Minecraft chat. */
    static final int MAX_MATRIX_TO_MC_CHARS = 512;
    /** A sync gap (limited timeline) is filled with at most this many pages of history. */
    private static final int MAX_BACKFILL_PAGES = 10;
    private static final int BACKFILL_PAGE_SIZE = 100;
    private static final int REPLY_QUEUE_SIZE = 32;

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private volatile BridgeSettings settings;
    private volatile MatrixClient matrixClient;
    private SinceStore sinceStore;
    private EventDeduplicator dedup;
    private McCallbacks callbacks;

    private BlockingQueue<String> outgoingQueue;
    /** Bot replies; sent by their own thread so a rate-limited reply never blocks /sync. */
    private volatile BlockingQueue<String> replyQueue;

    private Thread bootstrapThread;
    private Thread senderThread;
    private Thread syncThread;
    private Thread replyThread;

    private volatile String selfUserId = "";
    private volatile String resolvedRoomId = "";
    private volatile boolean announcedConnected = false;
    private volatile long lastQueueFullLogMs = 0;
    private volatile long lastTapQueueFullLogMs = 0;
    private volatile long lastAuthErrorLogMs = 0;

    public void start(BridgeSettings settings, Path worldRoot, McCallbacks callbacks) {
        synchronized (lifecycleLock) {
            if (running.get()) {
                return;
            }
            reapTerminatedThreads();
            if (bootstrapThread != null || senderThread != null || syncThread != null || replyThread != null) {
                LOGGER.warning("MatrixBridge start ignored because the previous lifecycle is still stopping.");
                return;
            }

            this.settings = settings;
            this.callbacks = callbacks;
            this.selfUserId = "";
            this.resolvedRoomId = "";
            this.announcedConnected = false;
            this.ready.set(false);

            if (settings == null || !settings.isBridgeEnabled()) {
                LOGGER.info("MatrixBridge not started: both directions are disabled.");
                this.settings = null;
                this.callbacks = null;
                return;
            }
            if (!settings.hasRequiredFields()) {
                LOGGER.warning("MatrixBridge not started: configure homeserver, roomId, and access token (or MATRIX_ACCESS_TOKEN env var).");
                this.settings = null;
                this.callbacks = null;
                return;
            }

            this.matrixClient = new MatrixClient(settings.homeserver, settings.accessToken);
            this.sinceStore = new SinceStore(worldRoot);
            this.dedup = new EventDeduplicator(settings.dedupSize);
            this.outgoingQueue = new ArrayBlockingQueue<>(settings.maxQueueSize);
            this.replyQueue = new ArrayBlockingQueue<>(REPLY_QUEUE_SIZE);

            running.set(true);

            this.bootstrapThread = workerThread(this::bootstrap, "MatrixBridge-Bootstrap");
            this.bootstrapThread.start();
        }
    }

    public void stop() {
        Thread bt;
        Thread st;
        Thread syt;
        Thread rt;
        synchronized (lifecycleLock) {
            if (!running.get() && bootstrapThread == null && senderThread == null && syncThread == null
                    && replyThread == null) {
                return;
            }
            running.set(false);
            ready.set(false);
            bt = bootstrapThread;
            st = senderThread;
            syt = syncThread;
            rt = replyThread;
        }

        interrupt(bt);
        interrupt(st);
        interrupt(syt);
        interrupt(rt);

        join(bt);
        join(st);
        join(syt);
        join(rt);

        synchronized (lifecycleLock) {
            reapTerminatedThreads();
            if (bootstrapThread == null && senderThread == null && syncThread == null && replyThread == null) {
                clearLifecycleState();
            } else {
                LOGGER.warning("MatrixBridge worker threads did not stop within 2 seconds; retaining lifecycle state until they exit.");
            }
        }
    }

    private void reapTerminatedThreads() {
        if (bootstrapThread != null && !bootstrapThread.isAlive()) {
            bootstrapThread = null;
        }
        if (senderThread != null && !senderThread.isAlive()) {
            senderThread = null;
        }
        if (syncThread != null && !syncThread.isAlive()) {
            syncThread = null;
        }
        if (replyThread != null && !replyThread.isAlive()) {
            replyThread = null;
        }
    }

    private void clearLifecycleState() {
        outgoingQueue = null;
        replyQueue = null;
        matrixClient = null;
        sinceStore = null;
        dedup = null;
        callbacks = null;
        settings = null;
        selfUserId = "";
        resolvedRoomId = "";
        announcedConnected = false;
    }

    public boolean enqueueMcMessage(String formattedText) {
        BlockingQueue<String> q = outgoingQueue;
        if (!running.get() || q == null || isMcToMatrixDisabledByConfig()) {
            // Without MC -> Matrix there is no sender thread; queued messages would never leave.
            return false;
        }
        boolean ok = q.offer(formattedText);
        if (!ok) {
            long now = System.currentTimeMillis();
            if (now - lastQueueFullLogMs > 30_000) {
                lastQueueFullLogMs = now;
                int max = settings != null ? settings.maxQueueSize : -1;
                LOGGER.warning("MatrixBridge outgoing queue is full (maxQueueSize=" + max + "); dropping messages.");
            }
        }
        return ok;
    }

    /**
     * Queues event-tap output. Taps may fill at most half of the queue, so a busy tap cannot
     * crowd out player chat while the homeserver is rate-limiting the bridge.
     */
    public boolean enqueueTapMessage(String formattedText) {
        BlockingQueue<String> q = outgoingQueue;
        BridgeSettings s = settings;
        if (!running.get() || q == null || s == null || !s.enableMcToMatrix) {
            return false;
        }
        if (q.size() >= Math.max(1, s.maxQueueSize / 2)) {
            long now = System.currentTimeMillis();
            if (now - lastTapQueueFullLogMs > 30_000) {
                lastTapQueueFullLogMs = now;
                LOGGER.warning("MatrixBridge outgoing queue is half full; dropping event tap output until it drains.");
            }
            return false;
        }
        return enqueueMcMessage(formattedText);
    }

    /** True when the running bridge was configured without MC -> Matrix forwarding. */
    public boolean isMcToMatrixDisabledByConfig() {
        BridgeSettings s = settings;
        return s != null && !s.enableMcToMatrix;
    }

    /*
     * Player-facing notices are formatted from the settings this bridge was started with, like
     * everything else it does, so editing the config file takes effect on the next reload only.
     */

    public boolean enqueuePlayerChat(String playerName, String message) {
        return enqueuePrefixed("<" + playerName + "> " + message);
    }

    public boolean enqueuePlayerJoinLeave(String playerName, boolean joined) {
        BridgeSettings s = settings;
        if (s == null || !s.enableJoinLeaveToMatrix) {
            return false;
        }
        return enqueuePrefixed("* " + playerName + (joined ? " joined the game" : " left the game"));
    }

    public boolean enqueueServerLifecycle(boolean started) {
        BridgeSettings s = settings;
        if (s == null || !s.enableServerLifecycleToMatrix) {
            return false;
        }
        return enqueuePrefixed(started ? "* Server started." : "* Server stopping.");
    }

    public boolean enqueuePlayerTestMessage(String text) {
        return enqueuePrefixed("[TEST] " + text);
    }

    private boolean enqueuePrefixed(String line) {
        BridgeSettings s = settings;
        if (s == null || !s.enableMcToMatrix) {
            return false;
        }
        return enqueueMcMessage(s.mcToMatrixPrefix + line);
    }

    /**
     * The room to name in a player's login notice, or null when no notice is due: the bridge is
     * not ready, or it was started with announceConnected off.
     */
    public String connectedNoticeRoom() {
        BridgeSettings s = settings;
        if (s == null || !s.announceConnected || !isReady()) {
            return null;
        }
        String room = s.roomId.startsWith("#") ? s.roomId : resolvedRoomId;
        return (room == null || room.isBlank()) ? "<unknown>" : room;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isReady() {
        return ready.get();
    }

    public int getQueueSize() {
        BlockingQueue<String> q = outgoingQueue;
        return q == null ? 0 : q.size();
    }

    public String getSelfUserId() {
        return selfUserId;
    }

    public String getResolvedRoomId() {
        return resolvedRoomId;
    }

    private void bootstrap() {
        try {
            String userId = resolveSelfUserIdWithRetry();
            if (!running.get() || userId == null || userId.isBlank()) {
                return;
            }
            selfUserId = userId;
            sinceStore.saveSelfUserId(userId);
            sinceStore.bindOwner(settings.homeserver + "|" + userId);

            String roomId = resolveRoomIdWithRetry(settings.roomId);
            if (!running.get() || roomId == null || roomId.isBlank()) {
                return;
            }
            resolvedRoomId = roomId;

            if (!verifyJoinedRoomWithRetry(roomId)) {
                return;
            }

            boolean receiving;
            synchronized (lifecycleLock) {
                if (!running.get() || settings == null) {
                    return;
                }
                if (settings.enableMcToMatrix) {
                    senderThread = workerThread(this::senderLoop, "MatrixBridge-Sender");
                    senderThread.start();
                }
                receiving = settings.enableMatrixToMc;
                if (receiving) {
                    syncThread = workerThread(this::syncLoop, "MatrixBridge-Sync");
                    syncThread.start();
                    replyThread = workerThread(this::replyLoop, "MatrixBridge-Reply");
                    replyThread.start();
                }
            }
            if (!receiving) {
                markReady();
            }
            // Otherwise the sync thread marks the bridge ready after its initial sync, which skips
            // the room history: a message sent after the "connected" notice must not be skipped.
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MatrixBridge bootstrap failed: " + e, e);
        }
    }

    private void markReady() {
        BridgeSettings activeSettings;
        McCallbacks activeCallbacks;
        synchronized (lifecycleLock) {
            if (!running.get() || settings == null || ready.get()) {
                return;
            }
            activeSettings = settings;
            activeCallbacks = callbacks;
            ready.set(true);
        }
        String userId = selfUserId;
        String roomId = resolvedRoomId;
        if (activeSettings.roomId != null && activeSettings.roomId.startsWith("#") && !activeSettings.roomId.equals(roomId)) {
            LOGGER.info("MatrixBridge started as " + userId + " (room alias " + activeSettings.roomId + " -> " + roomId + ").");
        } else {
            LOGGER.info("MatrixBridge started as " + userId + " (room " + roomId + ").");
        }

        if (activeCallbacks != null && activeSettings.announceConnected && !announcedConnected) {
            announcedConnected = true;
            String displayRoom = activeSettings.roomId != null && activeSettings.roomId.startsWith("#")
                    ? activeSettings.roomId
                    : roomId;
            activeCallbacks.announceMatrixConnected(displayRoom);
        }
    }

    private String resolveSelfUserIdWithRetry() {
        long backoffMs = 1_000;
        boolean triedDiscovery = false;
        while (running.get()) {
            try {
                return matrixClient.whoami();
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix whoami failed (HTTP " + e.statusCode + "). Check access token / permissions; bridge will not run.");
                    running.set(false);
                    return null;
                }
                if (e.statusCode == 404 && !triedDiscovery) {
                    // The URL may be the server name rather than the client API. It may also be
                    // a reverse proxy whose route is not up yet, so keep retrying either way.
                    triedDiscovery = true;
                    if (useWellKnownClientApi()) {
                        continue;
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return null;
                    }
                    LOGGER.severe("Matrix whoami returned HTTP 404 at " + matrixClient.baseUrl()
                            + ". matrix.homeserver must be the client API URL (for example https://matrix.example.com),"
                            + " and no /.well-known/matrix/client delegation was found. Retrying in case the"
                            + " homeserver is still starting; fix the URL and run /matrix reload otherwise.");
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix whoami failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix whoami network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix whoami unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        return null;
    }

    /** Switches to the client API named by the configured URL's .well-known, if any. */
    private boolean useWellKnownClientApi() {
        MatrixClient current = matrixClient;
        try {
            String baseUrl = current.discoverClientBaseUrl();
            if (baseUrl.isBlank() || baseUrl.equals(current.baseUrl())) {
                return false;
            }
            LOGGER.info("MatrixBridge: " + current.baseUrl() + " delegates the client API to " + baseUrl
                    + " (/.well-known/matrix/client); using it.");
            matrixClient = new MatrixClient(baseUrl, settings.accessToken);
            return true;
        } catch (IOException e) {
            LOGGER.warning("MatrixBridge could not read /.well-known/matrix/client from " + current.baseUrl() + ": " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String resolveRoomIdWithRetry(String roomIdOrAlias) {
        String value = roomIdOrAlias == null ? "" : roomIdOrAlias.trim();
        if (value.isBlank()) {
            LOGGER.warning("MatrixBridge not started: missing matrix.roomId.");
            running.set(false);
            return null;
        }
        if (value.startsWith("!")) {
            return value;
        }
        if (!value.startsWith("#")) {
            LOGGER.warning("MatrixBridge not started: matrix.roomId must be a room ID (!...) or room alias (#...).");
            running.set(false);
            return null;
        }

        long backoffMs = 1_000;
        while (running.get()) {
            try {
                String roomId = matrixClient.resolveRoomAlias(value);
                if (roomId == null || roomId.isBlank()) {
                    LOGGER.severe("Matrix resolveRoomAlias returned empty room_id for alias " + value + "; bridge will not run.");
                    running.set(false);
                    return null;
                }
                if (!roomId.startsWith("!")) {
                    LOGGER.warning("Matrix resolveRoomAlias returned unexpected room_id '" + roomId + "' for alias " + value + "; continuing anyway.");
                }
                return roomId;
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix resolveRoomAlias failed (HTTP " + e.statusCode + "). Check access token / permissions; bridge will not run.");
                    running.set(false);
                    return null;
                }
                if (e.statusCode == 404) {
                    LOGGER.severe("Matrix resolveRoomAlias failed (HTTP 404). Check that the room alias is correct and the bot can access it: " + value);
                    running.set(false);
                    return null;
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix resolveRoomAlias failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix resolveRoomAlias network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix resolveRoomAlias unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        return null;
    }

    private String joinRoomWithRetry(String roomIdOrAlias) {
        long backoffMs = 1_000;
        while (running.get()) {
            try {
                return matrixClient.joinRoom(roomIdOrAlias);
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix joinRoom failed (HTTP " + e.statusCode + "). Check access token / permissions; bridge will not run.");
                    running.set(false);
                    return null;
                }
                if (e.statusCode >= 400 && e.statusCode < 500 && e.statusCode != 429) {
                    LOGGER.severe("Matrix joinRoom failed (HTTP " + e.statusCode + "). Not invited or cannot join room " + roomIdOrAlias);
                    running.set(false);
                    return null;
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix joinRoom failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix joinRoom network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix joinRoom unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        return null;
    }

    private boolean verifyJoinedRoomWithRetry(String roomId) {
        long backoffMs = 1_000;
        boolean attemptedJoin = false;
        while (running.get()) {
            try {
                Set<String> joined = matrixClient.joinedRooms();
                if (joined.contains(roomId)) {
                    return true;
                }

                // Not joined yet: attempt to accept invite by joining.
                if (!attemptedJoin) {
                    attemptedJoin = true;
                    String joinTarget = roomId;
                    LOGGER.info("MatrixBridge: user is not joined to " + roomId + "; attempting to join (accept invite)...");
                    String joinedRoomId = joinRoomWithRetry(joinTarget);
                    if (joinedRoomId == null) {
                        return false;
                    }
                    if (!joinedRoomId.isBlank() && !joinedRoomId.equals(roomId)) {
                        resolvedRoomId = joinedRoomId;
                        roomId = joinedRoomId;
                    }
                    continue;
                }

                String configured = settings != null ? settings.roomId : "";
                if (configured != null && configured.startsWith("#") && !configured.equals(roomId)) {
                    LOGGER.severe("MatrixBridge: room alias " + configured + " resolved to " + roomId + " but the user is NOT joined to that room (and auto-join failed). Ensure the user is invited and can join.");
                } else {
                    LOGGER.severe("MatrixBridge: user is NOT joined to roomId " + roomId + " (and auto-join failed). Ensure the user is invited and can join.");
                }
                running.set(false);
                return false;
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix joined_rooms failed (HTTP " + e.statusCode + "). Check access token / permissions; bridge will not run.");
                    running.set(false);
                    return false;
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix joined_rooms failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix joined_rooms network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix joined_rooms unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        return false;
    }

    private void senderLoop() {
        long backoffMs = 1_000;
        while (running.get()) {
            String msg;
            try {
                msg = outgoingQueue.take();
            } catch (InterruptedException e) {
                continue;
            }
            if (!running.get()) {
                return;
            }

            msg = appendQueuedLines(msg);
            String transactionId = UUID.randomUUID().toString();
            while (running.get()) {
                try {
                    String roomId = resolvedRoomId;
                    if (roomId == null || roomId.isBlank()) {
                        LOGGER.warning("MatrixBridge send skipped: roomId not resolved yet.");
                        break;
                    }
                    matrixClient.sendText(roomId, msg, transactionId);
                    backoffMs = 1_000;
                    break;
                } catch (MatrixClient.MatrixException e) {
                    if (e.statusCode == 429) {
                        long sleepMs = e.retryAfterMs > 0 ? e.retryAfterMs : jitter(backoffMs);
                        LOGGER.warning("Matrix send rate-limited; retrying in " + sleepMs + " ms.");
                        sleepMs(sleepMs);
                        backoffMs = nextBackoff(backoffMs);
                        continue;
                    }
                    if (e.statusCode == 401 || e.statusCode == 403) {
                        logAuthErrorOnce("Matrix send failed (HTTP " + e.statusCode + "). Check access token / permissions.");
                        sleepMs(60_000);
                        break;
                    }
                    if (e.statusCode >= 500) {
                        long sleepMs = jitter(backoffMs);
                        LOGGER.warning("Matrix send failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                        sleepMs(sleepMs);
                        backoffMs = nextBackoff(backoffMs);
                        continue;
                    }
                    LOGGER.warning("Matrix send failed (" + e.getMessage() + "); dropping message.");
                    break;
                } catch (IOException e) {
                    long sleepMs = jitter(backoffMs);
                    LOGGER.warning("Matrix send network error (" + e + "); retrying in " + sleepMs + " ms.");
                    sleepMs(sleepMs);
                    backoffMs = nextBackoff(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    long sleepMs = jitter(backoffMs);
                    LOGGER.warning("Matrix send unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                    sleepMs(sleepMs);
                    backoffMs = nextBackoff(backoffMs);
                }
            }
        }
    }

    /** Joins lines already waiting in the queue onto {@code first}; this is the only consumer. */
    private String appendQueuedLines(String first) {
        BlockingQueue<String> q = outgoingQueue;
        StringBuilder batch = new StringBuilder(first);
        int lines = 1;
        while (q != null && lines < MAX_BATCH_LINES) {
            String next = q.peek();
            if (next == null || batch.length() + 1 + next.length() > MAX_BATCH_CHARS) {
                break;
            }
            q.poll();
            batch.append('\n').append(next);
            lines++;
        }
        return batch.toString();
    }

    private void syncLoop() {
        String roomId = resolvedRoomId;
        String filterJson = buildFilterJson(roomId, settings.timelineLimit);
        String since = sinceStore.loadSince();

        if (since == null || since.isBlank()) {
            since = initialCatchup(filterJson);
        }
        if (!running.get()) {
            return;
        }
        markReady();

        long backoffMs = 1_000;
        while (running.get()) {
            try {
                MatrixClient.SyncResult result = matrixClient.syncOnce(since, settings.syncTimeoutMs, filterJson);
                // Save the token only after its messages went out, so a stop or a failed
                // backfill replays this batch instead of skipping the rest of it.
                if (!forwardMatrixMessages(result, since)) {
                    continue;
                }
                if (result.nextBatch != null && !result.nextBatch.isBlank()) {
                    since = result.nextBatch;
                    sinceStore.saveSince(since);
                }
                backoffMs = 1_000;
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix sync failed (HTTP " + e.statusCode + "). Check access token / permissions.");
                    sleepMs(60_000);
                    continue;
                }
                if (isRejectedSince(e, since)) {
                    // A homeserver reset or token from another server makes the since token
                    // permanently invalid; retrying it would stall Matrix -> MC forever.
                    LOGGER.warning("Matrix rejected the stored sync token (" + e.getMessage() + "); starting a fresh sync.");
                    sinceStore.saveSince("");
                    since = initialCatchup(filterJson);
                    backoffMs = 1_000;
                    continue;
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix sync failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix sync network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix sync unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
    }

    /**
     * A homeserver that does not accept a since token answers 400/404 with a Matrix error.
     * Other 4xx (a proxy's 408 or HTML 404 while restarting) say nothing about the token, and
     * dropping it would skip the messages sent in between.
     */
    private static boolean isRejectedSince(MatrixClient.MatrixException e, String since) {
        return since != null && !since.isBlank()
                && (e.statusCode == 400 || e.statusCode == 404) && !e.errcode.isBlank();
    }

    private String initialCatchup(String filterJson) {
        long backoffMs = 1_000;
        while (running.get()) {
            try {
                MatrixClient.SyncResult result = matrixClient.syncOnce("", 0, filterJson);
                if (result.nextBatch != null && !result.nextBatch.isBlank()) {
                    sinceStore.saveSince(result.nextBatch);
                    LOGGER.info("MatrixBridge initial sync complete; starting from next_batch.");
                    return result.nextBatch;
                }
                return "";
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix initial sync failed (HTTP " + e.statusCode + "). Check access token / permissions.");
                    running.set(false);
                    return "";
                }
                long sleepMs = (e.statusCode == 429 && e.retryAfterMs > 0) ? e.retryAfterMs : jitter(backoffMs);
                LOGGER.warning("Matrix initial sync failed (" + e.getMessage() + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix initial sync network error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception e) {
                long sleepMs = jitter(backoffMs);
                LOGGER.warning("Matrix initial sync unexpected error (" + e + "); retrying in " + sleepMs + " ms.");
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        return "";
    }

    /**
     * Forwards the room's timeline from one sync response, first filling a gap the homeserver
     * left by cutting the timeline ({@code limited}). Returns false when stopped part-way.
     */
    private boolean forwardMatrixMessages(MatrixClient.SyncResult result, String previousSince)
            throws IOException, InterruptedException, MatrixClient.MatrixException {
        if (!settings.enableMatrixToMc) {
            return true;
        }
        if (selfUserId == null || selfUserId.isBlank()) {
            return true;
        }

        String roomId = resolvedRoomId;
        if (roomId == null || roomId.isBlank()) {
            return true;
        }

        JsonElement roomEntry = result.joinedRooms.get(roomId);
        if (roomEntry == null || !roomEntry.isJsonObject()) {
            // Normal: /sync only includes rooms that have updates in that specific response.
            return true;
        }
        JsonObject roomObj = roomEntry.getAsJsonObject();
        JsonObject timeline = getObject(roomObj, "timeline");
        if (timeline == null) {
            return true;
        }
        List<JsonElement> events = new ArrayList<>();
        JsonElement limited = timeline.get("limited");
        String prevBatch = getString(timeline, "prev_batch");
        if (limited != null && limited.isJsonPrimitive() && limited.getAsBoolean()
                && !prevBatch.isBlank() && previousSince != null && !previousSince.isBlank()) {
            events.addAll(backfill(roomId, prevBatch, previousSince));
        }
        JsonArray timelineEvents = getArray(timeline, "events");
        if (timelineEvents != null) {
            timelineEvents.forEach(events::add);
        }

        for (JsonElement el : events) {
            if (!running.get()) {
                return false;
            }
            if (el != null && el.isJsonObject()) {
                forwardMatrixEvent(roomId, el.getAsJsonObject());
            }
        }
        return true;
    }

    /**
     * Fetches the messages between the previous sync token and the start of a cut timeline,
     * oldest first. Duplicates of timeline events are dropped later by event ID.
     */
    private List<JsonElement> backfill(String roomId, String prevBatch, String previousSince)
            throws IOException, InterruptedException, MatrixClient.MatrixException {
        String filter = "{\"types\":[\"m.room.message\"]}";
        List<JsonElement> newestFirst = new ArrayList<>();
        String from = prevBatch;
        for (int page = 0; page < MAX_BACKFILL_PAGES; page++) {
            MatrixClient.MessagesPage result;
            try {
                result = matrixClient.roomMessagesBackwards(roomId, from, previousSince, BACKFILL_PAGE_SIZE, filter);
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode >= 400 && e.statusCode < 500 && e.statusCode != 429) {
                    // Not retryable: forward what the sync returned rather than stall on the gap.
                    LOGGER.warning("MatrixBridge could not fetch messages missed during a sync gap (" + e.getMessage() + ").");
                    break;
                }
                throw e;
            }
            result.chunk.forEach(newestFirst::add);
            if (result.chunk.isEmpty() || result.end.isBlank() || result.end.equals(from)) {
                Collections.reverse(newestFirst);
                return newestFirst;
            }
            from = result.end;
        }
        if (!newestFirst.isEmpty()) {
            LOGGER.warning("MatrixBridge sync gap exceeded " + (MAX_BACKFILL_PAGES * BACKFILL_PAGE_SIZE)
                    + " messages; older missed messages were not forwarded.");
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private void forwardMatrixEvent(String roomId, JsonObject evt) {
        String type = getString(evt, "type");
        if (!"m.room.message".equals(type)) {
            return;
        }
        String eventId = getString(evt, "event_id");
        if (dedup.seenOrAdd(eventId)) {
            return;
        }

        String sender = getString(evt, "sender");
        if (sender.isBlank() || sender.equals(selfUserId)) {
            return;
        }

        JsonObject content = getObject(evt, "content");
        if (content == null) {
            return;
        }
        JsonObject relatesTo = getObject(content, "m.relates_to");
        boolean edit = relatesTo != null && "m.replace".equals(getString(relatesTo, "rel_type"));
        if (edit) {
            // An edit's body is "* new text"; the replacement lives in m.new_content.
            content = getObject(content, "m.new_content");
            if (content == null) {
                return;
            }
        }
        String msgType = getString(content, "msgtype");
        if (!"m.text".equals(msgType)) {
            return;
        }
        String rawBody = getString(content, "body");
        if (!edit && relatesTo != null && getObject(relatesTo, "m.in_reply_to") != null) {
            rawBody = stripReplyFallback(rawBody);
        }
        String body = sanitizeForMinecraft(rawBody);
        if (body.isBlank()) {
            return;
        }

        if (edit) {
            // Editing a message into a command must not run it (again).
            if (isBotCommand(body)) {
                return;
            }
        } else if (maybeHandleMatrixBotCommand(sender, body)) {
            return;
        }
        body = EventSerializationUtil.truncate(body, MAX_MATRIX_TO_MC_CHARS);
        if (edit) {
            body = body + " (edited)";
        }

        String displaySender = sender;
        MatrixClient client = matrixClient;
        if (client != null) {
            try {
                String dn = sanitizeForMinecraft(client.getRoomMemberDisplayName(roomId, sender));
                if (!dn.isBlank()) {
                    displaySender = dn;
                }
            } catch (Exception ignored) {
            }
        }

        String line = settings.matrixToMcPrefix + "<" + sanitizeForMinecraft(displaySender) + "> " + body;
        McCallbacks cb = callbacks;
        if (cb != null) {
            cb.broadcast(line);
        }
    }

    /**
     * Drops the quoted fallback ("> <@user> original" lines and the blank line after them)
     * that clients put in front of a reply's text.
     */
    static String stripReplyFallback(String body) {
        if (body == null || !body.startsWith(">")) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith(">")) {
            i++;
        }
        if (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
    }

    private boolean isBotCommand(String body) {
        if (body == null) {
            return false;
        }
        String prefix = (settings == null) ? "" : settings.matrixBotPrefix;
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        String trimmed = body.trim();
        if (!trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        return trimmed.length() == prefix.length() || Character.isWhitespace(trimmed.charAt(prefix.length()));
    }

    private boolean maybeHandleMatrixBotCommand(String senderMxid, String body) {
        if (!isBotCommand(body)) {
            return false;
        }
        String prefix = settings.matrixBotPrefix;
        String trimmed = body.trim();

        String rest = trimmed.substring(prefix.length()).trim();
        String[] parts = tokenizeCommand(rest).toArray(new String[0]);
        String sub = (parts.length >= 1) ? parts[0].toLowerCase(Locale.ROOT) : "help";

        if ("help".equals(sub)) {
            sendMatrixBotReply("Commands: " + prefix + " help, " + prefix + " list, " + prefix + " event");
            return true;
        }

        if ("list".equals(sub)) {
            sendMatrixBotReply(buildOnlinePlayersReply(Duration.ofSeconds(2)));
            return true;
        }

        if ("event".equals(sub)) {
            handleEventCommand(senderMxid, parts);
            return true;
        }

        sendMatrixBotReply("Unknown command. Try: " + prefix + " help");
        return true;
    }

    private void handleEventCommand(String senderMxid, String[] parts) {
        if (!settings.enableMatrixToMc) {
            sendMatrixBotReply("Event tap commands require Matrix→MC sync to be enabled.");
            return;
        }

        if (!settings.enableEventTaps) {
            sendMatrixBotReply("Event taps are disabled in configuration.");
            return;
        }

        int minPowerLevel = settings.eventCommandMinPowerLevel;
        int userPowerLevel = 0;
        try {
            userPowerLevel = matrixClient.getUserPowerLevel(resolvedRoomId, senderMxid);
        } catch (Exception e) {
            LOGGER.warning("Failed to get power level for " + senderMxid + ": " + e.getMessage());
            sendMatrixBotReply("Failed to verify permissions. Please try again.");
            return;
        }

        if (userPowerLevel < minPowerLevel) {
            sendMatrixBotReply("You need power level " + minPowerLevel + " or higher to use event commands (you have " + userPowerLevel + ").");
            return;
        }

        String[] eventArgs = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];

        McCallbacks cb = callbacks;
        if (cb == null) {
            sendMatrixBotReply("Event tap commands are not available.");
            return;
        }

        try {
            CompletableFuture<String> fut = cb.handleEventTapCommand(senderMxid, eventArgs);
            if (fut == null) {
                sendMatrixBotReply("Event tap commands are not supported.");
                return;
            }
            String reply = fut.get(5, TimeUnit.SECONDS);
            sendMatrixBotReply(reply != null ? reply : "No response from event handler.");
        } catch (java.util.concurrent.TimeoutException e) {
            sendMatrixBotReply("Event command timed out.");
        } catch (Exception e) {
            LOGGER.warning("Event command failed: " + e);
            sendMatrixBotReply("Event command failed: " + e.getMessage());
        }
    }

    private String buildOnlinePlayersReply(Duration timeout) {
        McCallbacks cb = callbacks;
        if (cb == null) {
            return "Online players: <unavailable>";
        }

        try {
            CompletableFuture<List<String>> fut = cb.getOnlinePlayerNames();
            if (fut == null) {
                return "Online players: <unavailable>";
            }
            long ms = timeout == null ? 2_000 : Math.max(1, timeout.toMillis());
            List<String> names = fut.get(ms, TimeUnit.MILLISECONDS);
            if (names == null || names.isEmpty()) {
                return "No players online.";
            }
            if (names.size() == 1) {
                return "Online players (1): " + names.get(0);
            }
            return "Online players (" + names.size() + "): " + String.join(", ", names);
        } catch (Exception ignored) {
            return "Online players: <unavailable>";
        }
    }

    /** Queues a bot reply for the reply thread; the sync thread never waits on Matrix sends. */
    private void sendMatrixBotReply(String text) {
        BlockingQueue<String> q = replyQueue;
        if (text == null || text.isBlank() || q == null) {
            return;
        }
        if (!q.offer(text)) {
            LOGGER.warning("MatrixBridge bot reply queue is full; dropping a reply.");
        }
    }

    private void replyLoop() {
        while (running.get()) {
            BlockingQueue<String> q = replyQueue;
            if (q == null) {
                return;
            }
            String text;
            try {
                text = q.take();
            } catch (InterruptedException e) {
                continue;
            }
            deliverBotReply(text);
        }
    }

    private void deliverBotReply(String text) {
        MatrixClient client = matrixClient;
        if (client == null) {
            return;
        }
        String roomId = resolvedRoomId;
        if (roomId == null || roomId.isBlank()) {
            return;
        }

        long backoffMs = 1_000;
        String transactionId = UUID.randomUUID().toString();
        for (int attempt = 0; running.get() && attempt < 3; attempt++) {
            try {
                client.sendText(roomId, text, transactionId);
                return;
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 429) {
                    long sleepMs = e.retryAfterMs > 0 ? e.retryAfterMs : jitter(backoffMs);
                    sleepMs(sleepMs);
                    backoffMs = nextBackoff(backoffMs);
                    continue;
                }
                LOGGER.warning("Matrix bot reply failed (" + e.getMessage() + "); dropping.");
                return;
            } catch (IOException e) {
                long sleepMs = jitter(backoffMs);
                sleepMs(sleepMs);
                backoffMs = nextBackoff(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.warning("Matrix bot reply unexpected error (" + e + "); dropping.");
                return;
            }
        }
    }

    /**
     * Splits a bot command into arguments. Double quotes (straight, or the curly quotes
     * that mobile keyboards substitute) group words, and {@code ""} is an empty argument,
     * so {@code event on X "" 10m} skips the filter. An unterminated quote runs to the end.
     */
    static List<String> tokenizeCommand(String text) {
        List<String> tokens = new java.util.ArrayList<>();
        if (text == null) {
            return tokens;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"' || c == '\u201c') {
                char close = c == '"' ? '"' : '\u201d';
                int end = text.indexOf(close, i + 1);
                tokens.add(end < 0 ? text.substring(i + 1) : text.substring(i + 1, end));
                i = end < 0 ? n : end + 1;
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            tokens.add(text.substring(start, i));
        }
        return tokens;
    }

    /** Matrix only needs this room's message timeline; skip everything else the server would send. */
    private static String buildFilterJson(String roomId, int timelineLimit) {
        JsonObject timeline = new JsonObject();
        timeline.addProperty("limit", timelineLimit);
        JsonArray types = new JsonArray();
        types.add("m.room.message");
        timeline.add("types", types);

        JsonObject room = new JsonObject();
        if (roomId != null && !roomId.isBlank()) {
            JsonArray rooms = new JsonArray();
            rooms.add(roomId);
            room.add("rooms", rooms);
        }
        room.add("timeline", timeline);
        room.add("state", excludeAll());
        room.add("ephemeral", excludeAll());
        room.add("account_data", excludeAll());

        JsonObject filter = new JsonObject();
        filter.add("room", room);
        filter.add("presence", excludeAll());
        filter.add("account_data", excludeAll());
        return filter.toString();
    }

    private static JsonObject excludeAll() {
        JsonArray all = new JsonArray();
        all.add("*");
        JsonObject eventFilter = new JsonObject();
        eventFilter.add("not_types", all);
        return eventFilter;
    }

    /**
     * Makes Matrix text safe for Minecraft chat: drops section-sign formatting codes
     * (so remote users cannot obfuscate or recolour messages) and invisible format
     * characters (bidi overrides such as U+202E, zero-width spaces), and turns control
     * characters, including newlines, into spaces.
     */
    static String sanitizeForMinecraft(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\u00a7') {
                if (i < text.length()) {
                    i += Character.charCount(text.codePointAt(i));
                }
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT) {
                continue;
            }
            if (Character.isISOControl(cp)) {
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString().trim();
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null) {
            return "";
        }
        JsonElement e = obj.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
    }

    private static JsonObject getObject(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonElement e = obj.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray getArray(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonElement e = obj.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    /**
     * Bridge workers never hold state that must outlive the server, and loaders skip
     * the stopping event when the tick loop crashes. Daemon threads let the JVM exit.
     */
    private static Thread workerThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void interrupt(Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }

    private static void join(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleepMs(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long nextBackoff(long currentMs) {
        return Math.min(60_000, Math.max(1_000, currentMs) * 2);
    }

    private static long jitter(long baseMs) {
        long clamped = Math.max(250, Math.min(60_000, baseMs));
        long delta = (long) (clamped * 0.2);
        long min = Math.max(0, clamped - delta);
        long max = clamped + delta;
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private void logAuthErrorOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastAuthErrorLogMs > 60_000) {
            lastAuthErrorLogMs = now;
            LOGGER.severe(message);
        }
    }
}
