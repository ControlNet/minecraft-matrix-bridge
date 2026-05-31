package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeService {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private BridgeSettings settings;
    private MatrixClient matrixClient;
    private SinceStore sinceStore;
    private EventDeduplicator dedup;
    private McCallbacks callbacks;

    private BlockingQueue<String> outgoingQueue;

    private Thread bootstrapThread;
    private Thread senderThread;
    private Thread syncThread;

    private volatile String selfUserId = "";
    private volatile String resolvedRoomId = "";
    private volatile boolean announcedConnected = false;
    private volatile long lastQueueFullLogMs = 0;
    private volatile long lastAuthErrorLogMs = 0;

    public void start(BridgeSettings settings, Path worldRoot, McCallbacks callbacks) {
        synchronized (lifecycleLock) {
            if (running.get()) {
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

            running.set(true);

            this.bootstrapThread = new Thread(this::bootstrap, "MatrixBridge-Bootstrap");
            this.bootstrapThread.start();
        }
    }

    public void stop() {
        Thread bt;
        Thread st;
        Thread syt;
        synchronized (lifecycleLock) {
            if (!running.get() && bootstrapThread == null && senderThread == null && syncThread == null) {
                return;
            }
            running.set(false);
            ready.set(false);
            bt = bootstrapThread;
            st = senderThread;
            syt = syncThread;
        }

        interrupt(bt);
        interrupt(st);
        interrupt(syt);

        join(bt);
        join(st);
        join(syt);

        synchronized (lifecycleLock) {
            bootstrapThread = null;
            senderThread = null;
            syncThread = null;
            outgoingQueue = null;
            matrixClient = null;
            sinceStore = null;
            dedup = null;
            callbacks = null;
            settings = null;
            selfUserId = "";
            resolvedRoomId = "";
            announcedConnected = false;
        }
    }

    public boolean enqueueMcMessage(String formattedText) {
        BlockingQueue<String> q = outgoingQueue;
        if (!running.get() || q == null) {
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

            String roomId = resolveRoomIdWithRetry(settings.roomId);
            if (!running.get() || roomId == null || roomId.isBlank()) {
                return;
            }
            resolvedRoomId = roomId;

            if (!verifyJoinedRoomWithRetry(roomId)) {
                return;
            }

            if (settings.enableMcToMatrix) {
                senderThread = new Thread(this::senderLoop, "MatrixBridge-Sender");
                senderThread.start();
            }
            if (settings.enableMatrixToMc) {
                syncThread = new Thread(this::syncLoop, "MatrixBridge-Sync");
                syncThread.start();
            }

            ready.set(true);
            if (settings.roomId != null && settings.roomId.startsWith("#") && !settings.roomId.equals(roomId)) {
                LOGGER.info("MatrixBridge started as " + userId + " (room alias " + settings.roomId + " -> " + roomId + ").");
            } else {
                LOGGER.info("MatrixBridge started as " + userId + " (room " + roomId + ").");
            }

            McCallbacks cb = callbacks;
            if (cb != null && settings.announceConnected && !announcedConnected) {
                announcedConnected = true;
                String displayRoom = settings.roomId != null && settings.roomId.startsWith("#")
                        ? settings.roomId
                        : roomId;
                cb.announceMatrixConnected(displayRoom);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "MatrixBridge bootstrap failed: " + e, e);
        }
    }

    private String resolveSelfUserIdWithRetry() {
        long backoffMs = 1_000;
        while (running.get()) {
            try {
                return matrixClient.whoami();
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix whoami failed (HTTP " + e.statusCode + "). Check access token / permissions; bridge will not run.");
                    running.set(false);
                    return null;
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

            while (running.get()) {
                try {
                    String roomId = resolvedRoomId;
                    if (roomId == null || roomId.isBlank()) {
                        LOGGER.warning("MatrixBridge send skipped: roomId not resolved yet.");
                        break;
                    }
                    matrixClient.sendText(roomId, msg);
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

    private void syncLoop() {
        String roomId = resolvedRoomId;
        String filterJson = buildFilterJson(roomId, settings.timelineLimit);
        String since = sinceStore.loadSince();

        if (since == null || since.isBlank()) {
            since = initialCatchup(filterJson);
        }

        long backoffMs = 1_000;
        while (running.get()) {
            try {
                MatrixClient.SyncResult result = matrixClient.syncOnce(since, settings.syncTimeoutMs, filterJson);
                if (result.nextBatch != null && !result.nextBatch.isBlank()) {
                    since = result.nextBatch;
                    sinceStore.saveSince(since);
                }

                forwardMatrixMessages(result);
                backoffMs = 1_000;
            } catch (MatrixClient.MatrixException e) {
                if (e.statusCode == 401 || e.statusCode == 403) {
                    logAuthErrorOnce("Matrix sync failed (HTTP " + e.statusCode + "). Check access token / permissions.");
                    sleepMs(60_000);
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

    private void forwardMatrixMessages(MatrixClient.SyncResult result) {
        if (!settings.enableMatrixToMc) {
            return;
        }
        if (selfUserId == null || selfUserId.isBlank()) {
            return;
        }

        String roomId = resolvedRoomId;
        if (roomId == null || roomId.isBlank()) {
            return;
        }

        JsonElement roomEntry = result.joinedRooms.get(roomId);
        if (roomEntry == null || !roomEntry.isJsonObject()) {
            // Normal: /sync only includes rooms that have updates in that specific response.
            return;
        }
        JsonObject roomObj = roomEntry.getAsJsonObject();
        JsonObject timeline = getObject(roomObj, "timeline");
        if (timeline == null) {
            return;
        }
        JsonArray events = getArray(timeline, "events");
        if (events == null || events.isEmpty()) {
            return;
        }

        for (JsonElement el : events) {
            if (!running.get()) {
                return;
            }
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject evt = el.getAsJsonObject();

            String type = getString(evt, "type");
            if (!"m.room.message".equals(type)) {
                continue;
            }
            String eventId = getString(evt, "event_id");
            if (dedup.seenOrAdd(eventId)) {
                continue;
            }

            String sender = getString(evt, "sender");
            if (sender.isBlank() || sender.equals(selfUserId)) {
                continue;
            }

            JsonObject content = getObject(evt, "content");
            if (content == null) {
                continue;
            }
            String msgType = getString(content, "msgtype");
            if (!"m.text".equals(msgType)) {
                continue;
            }
            String body = getString(content, "body");
            body = sanitizeOneLine(body);
            if (body.isBlank()) {
                continue;
            }

            if (maybeHandleMatrixBotCommand(sender, body)) {
                continue;
            }

            String displaySender = sender;
            MatrixClient client = matrixClient;
            if (client != null) {
                try {
                    String dn = client.getRoomMemberDisplayName(roomId, sender);
                    if (dn != null && !dn.isBlank()) {
                        displaySender = dn;
                    }
                } catch (Exception ignored) {
                }
            }

            String line = settings.matrixToMcPrefix + "<" + displaySender + "> " + body;
            McCallbacks cb = callbacks;
            if (cb != null) {
                cb.broadcast(line);
            }
        }
    }

    private boolean maybeHandleMatrixBotCommand(String senderMxid, String body) {
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
        if (trimmed.length() != prefix.length()) {
            if (trimmed.length() < prefix.length() + 1) {
                return false;
            }
            char next = trimmed.charAt(prefix.length());
            if (!Character.isWhitespace(next)) {
                return false;
            }
        }

        String rest = trimmed.substring(prefix.length()).trim();
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
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

    private void sendMatrixBotReply(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        MatrixClient client = matrixClient;
        if (client == null) {
            return;
        }
        String roomId = resolvedRoomId;
        if (roomId == null || roomId.isBlank()) {
            return;
        }

        long backoffMs = 1_000;
        for (int attempt = 0; running.get() && attempt < 3; attempt++) {
            try {
                client.sendText(roomId, text);
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

        JsonObject filter = new JsonObject();
        filter.add("room", room);
        return filter.toString();
    }

    private static String sanitizeOneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
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
