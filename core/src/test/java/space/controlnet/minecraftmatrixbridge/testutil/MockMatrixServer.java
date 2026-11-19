package space.controlnet.minecraftmatrixbridge.testutil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class MockMatrixServer implements AutoCloseable {
    public record RecordedRequest(String method, String path, String query, String contentType, String body) {
    }

    private final HttpServer server;
    private final String expectedToken;
    private final String selfUserId;

    private final Queue<JsonObject> syncQueue = new ConcurrentLinkedQueue<>();
    private final List<RecordedRequest> sendRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedRequest> directoryRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedRequest> joinedRoomsRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedRequest> joinRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedRequest> syncRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<RecordedRequest> messagesRequests = Collections.synchronizedList(new ArrayList<>());
    /** /rooms/{id}/messages pages keyed by their {@code from} token. */
    private final Map<String, JsonObject> messagesPages = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> stateContents = new ConcurrentHashMap<>();
    private final Map<String, Integer> stateErrors = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> roomEvents = new ConcurrentHashMap<>();
    private final AtomicInteger whoamiCalls = new AtomicInteger();
    private final AtomicInteger sendCalls = new AtomicInteger();
    private final AtomicInteger resolveAliasCalls = new AtomicInteger();
    private final AtomicInteger joinedRoomsCalls = new AtomicInteger();
    private final AtomicInteger joinCalls = new AtomicInteger();

    private volatile String lastNextBatch = "";
    private volatile int whoami429Remaining = 0;
    private volatile long whoamiRetryAfterMs = 0;
    private volatile int send429Remaining = 0;
    private volatile long sendRetryAfterMs = 0;
    private volatile int joinedRooms429Remaining = 0;
    private volatile long joinedRoomsRetryAfterMs = 0;
    private volatile int join429Remaining = 0;
    private volatile long joinRetryAfterMs = 0;
    private volatile int sync429Remaining = 0;
    private volatile long syncRetryAfterMs = 0;
    private volatile int syncErrorRemaining = 0;
    private volatile int syncErrorStatus = 0;
    private volatile String syncErrorCode = "";
    private volatile boolean whoamiNotFound = false;
    private volatile String wellKnownBaseUrl = "";
    private volatile String wellKnownRedirect = "";
    private final AtomicInteger wellKnownCalls = new AtomicInteger();

    private volatile String roomAlias = "";
    private volatile String roomIdForAlias = "";
    private final Set<String> joinedRooms = ConcurrentHashMap.newKeySet();
    private final Set<String> invitedRooms = ConcurrentHashMap.newKeySet();
    private final Map<String, String> joinableAliases = new ConcurrentHashMap<>();
    private final Map<String, String> memberDisplayNames = new ConcurrentHashMap<>();

    public MockMatrixServer(String expectedToken, String selfUserId) throws IOException {
        this.expectedToken = Objects.requireNonNull(expectedToken, "expectedToken");
        this.selfUserId = Objects.requireNonNull(selfUserId, "selfUserId");

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_matrix/client/v3/account/whoami", this::handleWhoami);
        server.createContext("/_matrix/client/v3/directory/room", this::handleDirectoryRoom);
        server.createContext("/_matrix/client/v3/joined_rooms", this::handleJoinedRooms);
        server.createContext("/_matrix/client/v3/join", this::handleJoin);
        server.createContext("/_matrix/client/v3/sync", this::handleSync);
        server.createContext("/_matrix/client/v3/rooms", this::handleRooms);
        server.createContext("/.well-known/matrix/client", this::handleWellKnown);
        server.start();
    }

    public String homeserverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueueSync(JsonObject response) {
        syncQueue.add(Objects.requireNonNull(response, "response"));
    }

    public void enqueueSyncResponse(String roomId, String nextBatch, JsonArray events) {
        enqueueSyncResponse(roomId, nextBatch, events, false, "");
    }

    /** A sync whose timeline was cut to the newest events ({@code limited}), with its gap token. */
    public void enqueueSyncResponse(String roomId, String nextBatch, JsonArray events, boolean limited, String prevBatch) {
        JsonObject timeline = new JsonObject();
        timeline.add("events", events == null ? new JsonArray() : events);
        if (limited) {
            timeline.addProperty("limited", true);
            timeline.addProperty("prev_batch", prevBatch);
        }

        JsonObject roomObj = new JsonObject();
        roomObj.add("timeline", timeline);

        JsonObject join = new JsonObject();
        join.add(roomId, roomObj);

        JsonObject rooms = new JsonObject();
        rooms.add("join", join);

        JsonObject root = new JsonObject();
        root.addProperty("next_batch", nextBatch == null ? "" : nextBatch);
        root.add("rooms", rooms);

        enqueueSync(root);
    }

    public void setWhoamiRateLimit(int times, long retryAfterMs) {
        whoami429Remaining = Math.max(0, times);
        whoamiRetryAfterMs = Math.max(0, retryAfterMs);
    }

    public void setSendRateLimit(int times, long retryAfterMs) {
        send429Remaining = Math.max(0, times);
        sendRetryAfterMs = Math.max(0, retryAfterMs);
    }

    public void setJoinedRoomsRateLimit(int times, long retryAfterMs) {
        joinedRooms429Remaining = Math.max(0, times);
        joinedRoomsRetryAfterMs = Math.max(0, retryAfterMs);
    }

    public void setJoinRateLimit(int times, long retryAfterMs) {
        join429Remaining = Math.max(0, times);
        joinRetryAfterMs = Math.max(0, retryAfterMs);
    }

    public void setSyncRateLimit(int times, long retryAfterMs) {
        sync429Remaining = Math.max(0, times);
        syncRetryAfterMs = Math.max(0, retryAfterMs);
    }

    /** Makes whoami answer 404, like a server-name URL that does not host the client API. */
    public void setWhoamiNotFound(boolean notFound) {
        whoamiNotFound = notFound;
    }

    private volatile int whoamiErrorStatus;

    /** Answer every whoami with {@code status} (0 turns it off), like a retired endpoint. */
    public void setWhoamiError(int status) {
        whoamiErrorStatus = status;
    }

    /** Serves {@code /.well-known/matrix/client} pointing at {@code baseUrl}; blank means 404. */
    public void setWellKnownBaseUrl(String baseUrl) {
        wellKnownBaseUrl = baseUrl == null ? "" : baseUrl;
    }

    /** Answers {@code /.well-known/matrix/client} with a 302 to {@code location} (e.g. apex -> www). */
    public void setWellKnownRedirect(String location) {
        wellKnownRedirect = location == null ? "" : location;
    }

    public int wellKnownCallCount() {
        return wellKnownCalls.get();
    }

    /** Fail the next {@code times} sync requests with {@code status} and Matrix {@code errcode}. */
    public void setSyncError(int times, int status, String errcode) {
        syncErrorStatus = status;
        syncErrorCode = errcode == null ? "" : errcode;
        syncErrorRemaining = Math.max(0, times);
    }

    /** Serves one backwards page of {@code /messages} for {@code from}: newest first, {@code end} blank for the last page. */
    public void setMessagesPage(String from, JsonArray newestFirst, String end) {
        JsonObject page = new JsonObject();
        page.add("chunk", newestFirst);
        page.addProperty("start", from);
        if (end != null && !end.isBlank()) {
            page.addProperty("end", end);
        }
        messagesPages.put(from, page);
    }

    private volatile int messagesErrorRemaining;
    private volatile int messagesErrorStatus;
    private volatile String messagesErrorCode = "";
    private volatile int sendErrorRemaining;
    private volatile int sendErrorStatus;

    /** Fail the next {@code times} {@code /messages} requests with {@code status} and no Matrix errcode. */
    public void setMessagesError(int times, int status) {
        setMessagesError(times, status, "");
    }

    /** Fail the next {@code times} {@code /messages} requests with {@code status} and Matrix {@code errcode}. */
    public void setMessagesError(int times, int status, String errcode) {
        messagesErrorStatus = status;
        messagesErrorCode = errcode == null ? "" : errcode;
        messagesErrorRemaining = Math.max(0, times);
    }

    /** Fail the next {@code times} message sends with {@code status} and no Matrix errcode. */
    public void setSendError(int times, int status) {
        sendErrorStatus = status;
        sendErrorRemaining = Math.max(0, times);
    }

    private volatile int directoryErrorRemaining;
    private volatile int directoryErrorStatus;
    private volatile String directoryErrorCode = "";
    private volatile int joinErrorRemaining;
    private volatile int joinErrorStatus;
    private volatile String joinErrorCode = "";

    /** Fail the next {@code times} alias lookups with {@code status} and Matrix {@code errcode} (blank: none). */
    public void setDirectoryError(int times, int status, String errcode) {
        directoryErrorStatus = status;
        directoryErrorCode = errcode == null ? "" : errcode;
        directoryErrorRemaining = Math.max(0, times);
    }

    /** Fail the next {@code times} joins with {@code status} and Matrix {@code errcode} (blank: none). */
    public void setJoinError(int times, int status, String errcode) {
        joinErrorStatus = status;
        joinErrorCode = errcode == null ? "" : errcode;
        joinErrorRemaining = Math.max(0, times);
    }

    private void respondInjectedError(HttpExchange exchange, int status, String errcode) throws IOException {
        if (errcode.isBlank()) {
            respondPlain(exchange, status, "");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("errcode", errcode);
        body.addProperty("error", "mock error");
        respondJson(exchange, status, body);
    }

    public int pendingSyncErrors() {
        return syncErrorRemaining;
    }

    public List<RecordedRequest> getMessagesRequests() {
        synchronized (messagesRequests) {
            return List.copyOf(messagesRequests);
        }
    }

    public List<RecordedRequest> getSyncRequests() {
        synchronized (syncRequests) {
            return List.copyOf(syncRequests);
        }
    }

    /** Serve GET /rooms/{roomId}/state/{type}/{stateKey} with {@code content}. */
    public void setStateContent(String roomId, String type, String stateKey, JsonObject content) {
        stateContents.put(roomId + "|" + type + "|" + stateKey, content);
    }

    public void setStateError(String roomId, String type, String stateKey, int status) {
        stateErrors.put(roomId + "|" + type + "|" + stateKey, status);
    }

    /** Serve GET /rooms/{roomId}/event/{eventId} with {@code event}. */
    public void setRoomEvent(String roomId, String eventId, JsonObject event) {
        roomEvents.put(roomId + "|" + eventId, event);
    }

    public int whoamiCallCount() {
        return whoamiCalls.get();
    }

    public int sendCallCount() {
        return sendCalls.get();
    }

    public List<RecordedRequest> getSendRequests() {
        synchronized (sendRequests) {
            return new ArrayList<>(sendRequests);
        }
    }

    public void setRoomAliasMapping(String roomAlias, String roomId) {
        this.roomAlias = Objects.requireNonNull(roomAlias, "roomAlias");
        this.roomIdForAlias = Objects.requireNonNull(roomId, "roomId");
    }

    public int resolveAliasCallCount() {
        return resolveAliasCalls.get();
    }

    public List<RecordedRequest> getDirectoryRequests() {
        synchronized (directoryRequests) {
            return new ArrayList<>(directoryRequests);
        }
    }

    public void setJoinedRooms(List<String> roomIds) {
        joinedRooms.clear();
        if (roomIds != null) {
            for (String r : roomIds) {
                if (r != null && !r.isBlank()) {
                    joinedRooms.add(r);
                }
            }
        }
    }

    /** A public room that can be joined by {@code alias} (the server finds a route) but not by its bare ID. */
    public void setJoinableAlias(String alias, String roomId) {
        joinableAliases.put(alias, roomId);
    }

    public void setInvitedRooms(List<String> roomIds) {
        invitedRooms.clear();
        if (roomIds != null) {
            for (String r : roomIds) {
                if (r != null && !r.isBlank()) {
                    invitedRooms.add(r);
                }
            }
        }
    }

    public void setRoomMemberDisplayName(String roomId, String userId, String displayName) {
        String rid = roomId == null ? "" : roomId.trim();
        String uid = userId == null ? "" : userId.trim();
        String dn = displayName == null ? "" : displayName.trim();
        if (rid.isBlank() || uid.isBlank()) {
            return;
        }
        memberDisplayNames.put(rid + "|" + uid, dn);
    }

    public int joinCallCount() {
        return joinCalls.get();
    }

    public List<RecordedRequest> getJoinRequests() {
        synchronized (joinRequests) {
            return new ArrayList<>(joinRequests);
        }
    }

    public int joinedRoomsCallCount() {
        return joinedRoomsCalls.get();
    }

    public List<RecordedRequest> getJoinedRoomsRequests() {
        synchronized (joinedRoomsRequests) {
            return new ArrayList<>(joinedRoomsRequests);
        }
    }

    private void handleWhoami(HttpExchange exchange) throws IOException {
        whoamiCalls.incrementAndGet();

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        if (!checkAuth(exchange.getRequestHeaders())) {
            respondPlain(exchange, 401, "");
            return;
        }

        if (whoamiNotFound) {
            respondPlain(exchange, 404, "<html>Not Found</html>");
            return;
        }
        if (whoamiErrorStatus > 0) {
            respondPlain(exchange, whoamiErrorStatus, "");
            return;
        }

        if (whoami429Remaining > 0) {
            whoami429Remaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", "M_LIMIT_EXCEEDED");
            body.addProperty("error", "rate limited");
            body.addProperty("retry_after_ms", whoamiRetryAfterMs);
            // Spec recommends Retry-After header (seconds).
            if (whoamiRetryAfterMs > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(whoamiRetryAfterMs / 1000));
            }
            respondJson(exchange, 429, body);
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("user_id", selfUserId);
        respondJson(exchange, 200, body);
    }

    private void handleWellKnown(HttpExchange exchange) throws IOException {
        wellKnownCalls.incrementAndGet();
        String redirect = wellKnownRedirect;
        if (!redirect.isBlank()) {
            exchange.getResponseHeaders().set("Location", redirect);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        String baseUrl = wellKnownBaseUrl;
        if (baseUrl.isBlank()) {
            respondPlain(exchange, 404, "");
            return;
        }
        JsonObject homeserver = new JsonObject();
        homeserver.addProperty("base_url", baseUrl);
        JsonObject body = new JsonObject();
        body.add("m.homeserver", homeserver);
        respondJson(exchange, 200, body);
    }

    private void handleJoinedRooms(HttpExchange exchange) throws IOException {
        joinedRoomsCalls.incrementAndGet();
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        if (!checkAuth(exchange.getRequestHeaders())) {
            respondPlain(exchange, 401, "");
            return;
        }

        joinedRoomsRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                ""
        ));

        if (joinedRooms429Remaining > 0) {
            joinedRooms429Remaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", "M_LIMIT_EXCEEDED");
            body.addProperty("error", "rate limited");
            body.addProperty("retry_after_ms", joinedRoomsRetryAfterMs);
            if (joinedRoomsRetryAfterMs > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(joinedRoomsRetryAfterMs / 1000));
            }
            respondJson(exchange, 429, body);
            return;
        }

        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String r : joinedRooms) {
            arr.add(r);
        }
        body.add("joined_rooms", arr);
        respondJson(exchange, 200, body);
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        joinCalls.incrementAndGet();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        if (!checkAuth(exchange.getRequestHeaders())) {
            respondPlain(exchange, 401, "");
            return;
        }

        String path = exchange.getRequestURI().getRawPath();
        joinRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                ""
        ));

        if (joinErrorRemaining > 0) {
            joinErrorRemaining--;
            respondInjectedError(exchange, joinErrorStatus, joinErrorCode);
            return;
        }
        if (join429Remaining > 0) {
            join429Remaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", "M_LIMIT_EXCEEDED");
            body.addProperty("error", "rate limited");
            body.addProperty("retry_after_ms", joinRetryAfterMs);
            if (joinRetryAfterMs > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(joinRetryAfterMs / 1000));
            }
            respondJson(exchange, 429, body);
            return;
        }

        String prefix = "/_matrix/client/v3/join/";
        if (path == null || !path.startsWith(prefix)) {
            respondPlain(exchange, 404, "");
            return;
        }

        String rawRoom = path.substring(prefix.length());
        String room = URLDecoder.decode(rawRoom, StandardCharsets.UTF_8);

        String aliasTarget = joinableAliases.get(room);
        if (aliasTarget != null) {
            joinedRooms.add(aliasTarget);
            JsonObject ok = new JsonObject();
            ok.addProperty("room_id", aliasTarget);
            respondJson(exchange, 200, ok);
            return;
        }
        if (invitedRooms.contains(room)) {
            invitedRooms.remove(room);
            joinedRooms.add(room);
            JsonObject ok = new JsonObject();
            ok.addProperty("room_id", room);
            respondJson(exchange, 200, ok);
            return;
        }
        if (joinedRooms.contains(room)) {
            JsonObject ok = new JsonObject();
            ok.addProperty("room_id", room);
            respondJson(exchange, 200, ok);
            return;
        }

        JsonObject err = new JsonObject();
        err.addProperty("errcode", "M_FORBIDDEN");
        err.addProperty("error", "not invited");
        respondJson(exchange, 403, err);
    }

    private void handleDirectoryRoom(HttpExchange exchange) throws IOException {
        resolveAliasCalls.incrementAndGet();

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        // Per spec, room alias directory lookup does not require auth.

        String path = exchange.getRequestURI().getRawPath();
        directoryRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                ""
        ));

        if (directoryErrorRemaining > 0) {
            directoryErrorRemaining--;
            respondInjectedError(exchange, directoryErrorStatus, directoryErrorCode);
            return;
        }

        String prefix = "/_matrix/client/v3/directory/room/";
        if (path == null || !path.startsWith(prefix)) {
            respondPlain(exchange, 404, "");
            return;
        }

        String rawAlias = path.substring(prefix.length());
        String alias = URLDecoder.decode(rawAlias, StandardCharsets.UTF_8);
        if (roomAlias.isBlank() || roomIdForAlias.isBlank() || !roomAlias.equals(alias)) {
            respondPlain(exchange, 404, "");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("room_id", roomIdForAlias);
        JsonArray servers = new JsonArray();
        servers.add("example.com");
        body.add("servers", servers);
        respondJson(exchange, 200, body);
    }

    private void handleSync(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        if (!checkAuth(exchange.getRequestHeaders())) {
            respondPlain(exchange, 401, "");
            return;
        }

        syncRequests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), null, ""));

        if (syncErrorRemaining > 0) {
            syncErrorRemaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", syncErrorCode);
            body.addProperty("error", "mock sync error");
            respondJson(exchange, syncErrorStatus, body);
            return;
        }

        if (sync429Remaining > 0) {
            sync429Remaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", "M_LIMIT_EXCEEDED");
            body.addProperty("error", "rate limited");
            body.addProperty("retry_after_ms", syncRetryAfterMs);
            if (syncRetryAfterMs > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(syncRetryAfterMs / 1000));
            }
            respondJson(exchange, 429, body);
            return;
        }

        JsonObject next = syncQueue.poll();
        if (next == null) {
            next = new JsonObject();
            next.addProperty("next_batch", lastNextBatch);
            next.add("rooms", new JsonObject());
        } else {
            if (next.has("next_batch") && next.get("next_batch").isJsonPrimitive()) {
                lastNextBatch = next.get("next_batch").getAsString();
            }
        }
        respondJson(exchange, 200, next);
    }

    private void handleRooms(HttpExchange exchange) throws IOException {
        if (!checkAuth(exchange.getRequestHeaders())) {
            respondPlain(exchange, 401, "");
            return;
        }

        String path = exchange.getRequestURI().getRawPath();
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path != null && path.endsWith("/messages")) {
            handleMessages(exchange);
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleRoomMemberState(exchange, path);
            return;
        }
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondPlain(exchange, 405, "");
            return;
        }
        if (path == null || !path.contains("/send/m.room.message/")) {
            respondPlain(exchange, 404, "");
            return;
        }

        sendCalls.incrementAndGet();

        String bodyStr = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        sendRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                bodyStr
        ));

        if (sendErrorRemaining > 0) {
            sendErrorRemaining--;
            respondPlain(exchange, sendErrorStatus, "");
            return;
        }
        if (send429Remaining > 0) {
            send429Remaining--;
            JsonObject body = new JsonObject();
            body.addProperty("errcode", "M_LIMIT_EXCEEDED");
            body.addProperty("error", "rate limited");
            body.addProperty("retry_after_ms", sendRetryAfterMs);
            // Spec recommends Retry-After header (seconds).
            if (sendRetryAfterMs > 0) {
                exchange.getResponseHeaders().set("Retry-After", Long.toString(sendRetryAfterMs / 1000));
            }
            respondJson(exchange, 429, body);
            return;
        }

        JsonObject ok = new JsonObject();
        ok.addProperty("event_id", "$mock");
        respondJson(exchange, 200, ok);
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        messagesRequests.add(new RecordedRequest("GET", exchange.getRequestURI().getRawPath(), query, null, ""));
        if (messagesErrorRemaining > 0) {
            messagesErrorRemaining--;
            if (messagesErrorCode.isEmpty()) {
                respondPlain(exchange, messagesErrorStatus, "");
            } else {
                JsonObject body = new JsonObject();
                body.addProperty("errcode", messagesErrorCode);
                body.addProperty("error", "mock messages error");
                respondJson(exchange, messagesErrorStatus, body);
            }
            return;
        }
        String from = "";
        for (String part : (query == null ? "" : query).split("&")) {
            if (part.startsWith("from=")) {
                from = java.net.URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
            }
        }
        JsonObject page = messagesPages.get(from);
        if (page == null) {
            page = new JsonObject();
            page.add("chunk", new JsonArray());
            page.addProperty("start", from);
        }
        respondJson(exchange, 200, page);
    }

    private void handleRoomMemberState(HttpExchange exchange, String path) throws IOException {
        // GET /_matrix/client/v3/rooms/{roomId}/state/m.room.member/{userId}
        String prefix = "/_matrix/client/v3/rooms/";
        if (path == null || !path.startsWith(prefix)) {
            respondPlain(exchange, 404, "");
            return;
        }
        String rest = path.substring(prefix.length());
        String[] parts = rest.split("/", 4);
        if (parts.length == 3 && "event".equals(parts[1])) {
            String roomId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            JsonObject event = roomEvents.get(roomId + "|" + URLDecoder.decode(parts[2], StandardCharsets.UTF_8));
            if (event == null) {
                respondPlain(exchange, 404, "");
            } else {
                respondJson(exchange, 200, event);
            }
            return;
        }
        if (parts.length != 4) {
            respondPlain(exchange, 404, "");
            return;
        }
        String roomId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        String state = parts[1];
        String type = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
        String userId = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
        if ("state".equals(state)) {
            String key = roomId + "|" + type + "|" + userId;
            Integer error = stateErrors.get(key);
            if (error != null) {
                respondPlain(exchange, error, "");
                return;
            }
            JsonObject content = stateContents.get(key);
            if (content != null) {
                respondJson(exchange, 200, content);
                return;
            }
        }
        if (!"state".equals(state) || !"m.room.member".equals(type)) {
            respondPlain(exchange, 404, "");
            return;
        }

        String dn = memberDisplayNames.getOrDefault(roomId + "|" + userId, "");
        if (dn == null || dn.isBlank()) {
            respondPlain(exchange, 404, "");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("membership", "join");
        body.addProperty("displayname", dn);
        respondJson(exchange, 200, body);
    }

    private boolean checkAuth(Headers headers) {
        String auth = headers.getFirst("Authorization");
        return ("Bearer " + expectedToken).equals(auth);
    }

    private static void respondJson(HttpExchange exchange, int statusCode, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void respondPlain(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
