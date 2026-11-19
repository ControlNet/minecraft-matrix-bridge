package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class MatrixClient {
    private final HttpClient http;
    private final String homeserver;
    private final String accessToken;
    private final Duration requestTimeout;

    private static final int DISPLAYNAME_CACHE_SIZE = 512;
    private static final long DISPLAYNAME_TTL_MS = 10 * 60 * 1000L;
    private final Object displayNameLock = new Object();
    private final LinkedHashMap<String, DisplayNameEntry> displayNameCache = new LinkedHashMap<>(DISPLAYNAME_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DisplayNameEntry> eldest) {
            return size() > DISPLAYNAME_CACHE_SIZE;
        }
    };

    public MatrixClient(String homeserver, String accessToken) {
        this(homeserver, accessToken, Duration.ofSeconds(30));
    }

    MatrixClient(String homeserver, String accessToken, Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.homeserver = stripTrailingSlash(homeserver);
        this.accessToken = accessToken;
        this.requestTimeout = requestTimeout;
    }

    private HttpRequest.Builder newRequest() {
        return HttpRequest.newBuilder().timeout(requestTimeout);
    }

    /**
     * Sends {@code request} and reads the whole body within its timeout. HttpClient applies the
     * request timeout only until the response headers arrive, so a body that stalls part-way
     * (e.g. behind a proxy) would otherwise block the caller indefinitely.
     */
    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long timeoutMs = request.timeout().orElse(Duration.ofSeconds(30)).toMillis();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("Matrix request timed out after " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    String baseUrl() {
        return homeserver;
    }

    /**
     * Reads {@code m.homeserver.base_url} from {@code /.well-known/matrix/client} on the
     * configured URL, for setups where the configured URL is the server name rather than the
     * client API. Returns an empty string when there is no document (404). Any other answer
     * without a usable {@code m.homeserver.base_url} throws, since it says nothing about whether
     * the delegation still exists.
     */
    public String discoverClientBaseUrl() throws IOException, InterruptedException {
        HttpRequest request = newRequest()
                .uri(URI.create(homeserver + "/.well-known/matrix/client"))
                .GET()
                .build();
        // Unauthenticated, so following redirects (e.g. apex -> www) cannot leak the token;
        // NORMAL never follows https -> http.
        HttpClient discovery = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<String> response = send(discovery, request);
        if (response.statusCode() == 404) {
            return "";
        }
        if (response.statusCode() / 100 != 2) {
            // Says nothing about whether a delegation exists.
            throw new IOException("HTTP " + response.statusCode());
        }
        // JsonParser is lenient ("", bare words, unquoted names); a .well-known document is a
        // strict JSON object and anything else says nothing about the delegation.
        JsonElement parsed;
        try {
            com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                    new java.io.StringReader(response.body()));
            parsed = new com.google.gson.Gson().getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new IOException("trailing data");
            }
        } catch (RuntimeException e) {
            throw new IOException("unreadable response", e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("response is not a JSON object");
        }
        String baseUrl = stripTrailingSlash(getString(getObject(parsed.getAsJsonObject(), "m.homeserver"), "base_url"));
        // Never let a delegation downgrade an https homeserver to plain http.
        boolean usable = homeserver.startsWith("https://")
                ? baseUrl.startsWith("https://")
                : baseUrl.startsWith("https://") || baseUrl.startsWith("http://");
        if (!usable) {
            throw new IOException("no usable m.homeserver.base_url");
        }
        return baseUrl;
    }

    Duration syncRequestTimeout(int timeoutMs) {
        return requestTimeout.plusMillis(Math.max(0, timeoutMs));
    }

    public String whoami() throws IOException, InterruptedException, MatrixException {
        HttpRequest request = newRequest()
                .uri(URI.create(homeserver + "/_matrix/client/v3/account/whoami"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("whoami", response);
        }

        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement userId = obj.get("user_id");
        if (userId == null || !userId.isJsonPrimitive()) {
            throw new MatrixException("whoami: response missing user_id", response.statusCode(), 0);
        }
        return userId.getAsString();
    }

    public String resolveRoomAlias(String roomAlias) throws IOException, InterruptedException, MatrixException {
        String uri = homeserver + "/_matrix/client/v3/directory/room/" + urlEncodePath(roomAlias);

        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("resolveRoomAlias", response);
        }

        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement roomId = obj.get("room_id");
        if (roomId == null || !roomId.isJsonPrimitive()) {
            throw new MatrixException("resolveRoomAlias: response missing room_id", response.statusCode(), 0);
        }
        return roomId.getAsString();
    }

    public Set<String> joinedRooms() throws IOException, InterruptedException, MatrixException {
        HttpRequest request = newRequest()
                .uri(URI.create(homeserver + "/_matrix/client/v3/joined_rooms"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("joined_rooms", response);
        }

        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement rooms = obj.get("joined_rooms");
        Set<String> out = new HashSet<>();
        if (rooms != null && rooms.isJsonArray()) {
            JsonArray arr = rooms.getAsJsonArray();
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) {
                    String rid = el.getAsString();
                    if (rid != null && !rid.isBlank()) {
                        out.add(rid);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Accept an invitation / join a room. Works when the user is invited, or when the room is joinable.
     * <p>
     * Matrix API: POST /_matrix/client/v3/join/{roomIdOrAlias}
     */
    public String joinRoom(String roomIdOrAlias) throws IOException, InterruptedException, MatrixException {
        String value = roomIdOrAlias == null ? "" : roomIdOrAlias.trim();
        if (value.isBlank()) {
            throw new MatrixException("joinRoom: missing roomIdOrAlias", 0, 0);
        }

        String uri = homeserver + "/_matrix/client/v3/join/" + urlEncodePath(value);
        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("joinRoom", response);
        }

        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement roomId = obj.get("room_id");
        if (roomId == null || !roomId.isJsonPrimitive()) {
            // Some servers might return an empty body on success; treat that as "unknown".
            return "";
        }
        return roomId.getAsString();
    }

    /**
     * Gets the power level of a user in a room.
     * Matrix API: GET /_matrix/client/v3/rooms/{roomId}/state/m.room.power_levels
     *
     * @return the user's power level (default 0 if not found)
     */
    public int getUserPowerLevel(String roomId, String userId) throws IOException, InterruptedException, MatrixException {
        String rid = roomId == null ? "" : roomId.trim();
        String uid = userId == null ? "" : userId.trim();
        if (rid.isBlank() || uid.isBlank()) {
            return 0;
        }

        String uri = homeserver + "/_matrix/client/v3/rooms/" + urlEncodePath(rid) + "/state/m.room.power_levels/";

        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        int status = response.statusCode();
        if (status != 404 && status / 100 != 2) {
            throw toException("power_levels", response);
        }

        JsonObject powerLevels = null;
        if (status != 404) {
            try {
                JsonElement parsed = JsonParser.parseString(response.body());
                powerLevels = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
            } catch (Exception e) {
                return 0;
            }
        }

        if (powerLevels != null) {
            JsonObject users = getObject(powerLevels, "users");
            if (users != null) {
                JsonElement userLevel = users.get(uid);
                if (userLevel != null && userLevel.isJsonPrimitive()) {
                    return intOr(userLevel, 0);
                }
            }
        }

        // Room creators are not always listed in "users": in room v12+ they have
        // unbounded power, and without a power_levels event the creator has 100.
        RoomCreators creators = roomCreators(rid);
        if (creators.contains(uid)) {
            if (creators.privileged) {
                return Integer.MAX_VALUE;
            }
            if (powerLevels == null) {
                return 100;
            }
        }

        if (powerLevels == null) {
            return 0;
        }
        JsonElement usersDefault = powerLevels.get("users_default");
        return usersDefault != null && usersDefault.isJsonPrimitive() ? intOr(usersDefault, 0) : 0;
    }

    private static int intOr(JsonElement value, int fallback) {
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Room versions whose creators are ordinary power-level users (MSC4289 changed this in v12). */
    private static final Set<String> LEGACY_ROOM_VERSIONS =
            Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");

    private final java.util.concurrent.ConcurrentHashMap<String, RoomCreators> creatorsCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Looks up the room's creators. Lookup failures fail closed (no creator privilege)
     * and are not cached, so a later command can succeed.
     */
    private RoomCreators roomCreators(String roomId) throws InterruptedException {
        RoomCreators cached = creatorsCache.get(roomId);
        if (cached != null) {
            return cached;
        }
        try {
            JsonObject content = getJson(roomPath(roomId) + "/state/m.room.create/", "room_create");
            String version = getString(content, "room_version");
            if (version.isEmpty()) {
                version = "1"; // The spec default when room_version is absent.
            }
            Set<String> users = new java.util.HashSet<>();
            boolean privileged = !LEGACY_ROOM_VERSIONS.contains(version);
            if (privileged) {
                // v12 room IDs are the create event's reference hash, so the event ID is derivable.
                if (!roomId.startsWith("!") || roomId.contains(":")) {
                    throw new MatrixException("room " + roomId + " has version " + version
                            + " but a server-scoped room ID", 0, 0);
                }
                JsonObject event = getJson(roomPath(roomId) + "/event/" + urlEncodePath("$" + roomId.substring(1)), "room_create_event");
                String sender = getString(event, "sender");
                if (!sender.isEmpty()) {
                    users.add(sender);
                }
                JsonObject eventContent = getObject(event, "content");
                JsonElement additional = eventContent == null ? null : eventContent.get("additional_creators");
                if (additional != null && additional.isJsonArray()) {
                    for (JsonElement creator : additional.getAsJsonArray()) {
                        if (creator.isJsonPrimitive()) {
                            users.add(creator.getAsString());
                        }
                    }
                }
            } else {
                String creator = getString(content, "creator");
                if (!creator.isEmpty()) {
                    users.add(creator);
                }
            }
            RoomCreators result = new RoomCreators(Set.copyOf(users), privileged);
            creatorsCache.put(roomId, result);
            return result;
        } catch (IOException | MatrixException | RuntimeException e) {
            java.util.logging.Logger.getLogger("MatrixBridge").warning(
                    "MatrixBridge could not determine the creators of " + roomId + " (" + e.getMessage()
                            + "); treating creators as ordinary users.");
            return new RoomCreators(Set.of(), false);
        }
    }

    private String roomPath(String roomId) {
        return homeserver + "/_matrix/client/v3/rooms/" + urlEncodePath(roomId);
    }

    private JsonObject getJson(String uri, String op) throws IOException, InterruptedException, MatrixException {
        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException(op, response);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private record RoomCreators(Set<String> users, boolean privileged) {
        boolean contains(String userId) {
            return users.contains(userId);
        }
    }

    /**
     * Best-effort displayname lookup for a Matrix user in a room.
     * <p>
     * Uses room member state to get the per-room displayname:
     * GET /_matrix/client/v3/rooms/{roomId}/state/m.room.member/{userId}
     * <p>
     * Returns empty string if unknown/unset.
     */
    public String getRoomMemberDisplayName(String roomId, String userId) throws IOException, InterruptedException, MatrixException {
        String rid = roomId == null ? "" : roomId.trim();
        String uid = userId == null ? "" : userId.trim();
        if (rid.isBlank() || uid.isBlank()) {
            return "";
        }

        String key = rid + "|" + uid;
        long now = System.currentTimeMillis();
        synchronized (displayNameLock) {
            DisplayNameEntry cached = displayNameCache.get(key);
            if (cached != null && cached.expiresAtMs > now) {
                return cached.displayName;
            }
        }

        String uri = homeserver + "/_matrix/client/v3/rooms/" + urlEncodePath(rid)
                + "/state/m.room.member/" + urlEncodePath(uid);

        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        int status = response.statusCode();
        if (status == 404) {
            cacheDisplayName(key, "", now);
            return "";
        }
        if (status / 100 != 2) {
            throw toException("room_member", response);
        }

        String displayName = "";
        try {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonElement dn = obj.get("displayname");
            if (dn != null && dn.isJsonPrimitive()) {
                displayName = dn.getAsString();
            }
        } catch (Exception ignored) {
        }

        cacheDisplayName(key, displayName == null ? "" : displayName.trim(), now);
        return displayName == null ? "" : displayName.trim();
    }

    public void sendText(String roomId, String text) throws IOException, InterruptedException, MatrixException {
        sendText(roomId, text, UUID.randomUUID().toString());
    }

    /** Reuse the same transaction ID when retrying a logical message. */
    public void sendText(String roomId, String text, String txnId) throws IOException, InterruptedException, MatrixException {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID must not be blank");
        }
        String uri = homeserver + "/_matrix/client/v3/rooms/" + urlEncodePath(roomId)
                + "/send/m.room.message/" + urlEncodePath(txnId);

        JsonObject body = new JsonObject();
        body.addProperty("msgtype", "m.text");
        body.addProperty("body", text == null ? "" : text);
        // Relayed Minecraft chat is untrusted: an empty m.mentions stops clients from
        // treating "@room" or display names in the body as pings (Matrix 1.7+).
        body.add("m.mentions", new JsonObject());

        HttpRequest request = newRequest()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("sendText", response);
        }
    }

    public SyncResult syncOnce(String since, int timeoutMs, String filterJson)
            throws IOException, InterruptedException, MatrixException {
        StringBuilder sb = new StringBuilder(homeserver)
                .append("/_matrix/client/v3/sync?timeout=").append(timeoutMs)
                .append("&set_presence=offline");

        if (since != null && !since.isBlank()) {
            sb.append("&since=").append(urlEncodeQuery(since));
        }
        if (filterJson != null && !filterJson.isBlank()) {
            sb.append("&filter=").append(urlEncodeQuery(filterJson));
        }

        HttpRequest request = newRequest()
                .timeout(syncRequestTimeout(timeoutMs))
                .uri(URI.create(sb.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = send(http, request);
        if (response.statusCode() / 100 != 2) {
            throw toException("sync", response);
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonElement token = root.get("next_batch");
        // Without a position the response cannot be resumed from; treat it like a failed request.
        if (token == null || !token.isJsonPrimitive() || !token.getAsJsonPrimitive().isString()
                || token.getAsString().isBlank()) {
            throw new IOException("sync response without next_batch");
        }
        String nextBatch = token.getAsString();
        JsonObject joinedRooms = new JsonObject();
        JsonObject rooms = getObject(root, "rooms");
        if (rooms != null) {
            JsonObject join = getObject(rooms, "join");
            if (join != null) {
                joinedRooms = join;
            }
        }
        return new SyncResult(nextBatch, joinedRooms);
    }

    private static String stripTrailingSlash(String homeserver) {
        String s = homeserver == null ? "" : homeserver.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String urlEncodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlEncodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private static MatrixException toException(String op, HttpResponse<String> response) {
        int status = response.statusCode();
        long retryAfterMs = 0;
        if (status == 429) {
            retryAfterMs = Math.max(parseRetryAfterHeaderMs(response), parseRetryAfterMs(response.body()));
        }
        String error = parseMatrixError(response.body());
        String msg = op + ": HTTP " + status + (error.isBlank() ? "" : (" (" + error + ")"));
        return new MatrixException(msg, status, retryAfterMs, parseErrcode(response.body()));
    }

    private static long parseRetryAfterHeaderMs(HttpResponse<?> response) {
        try {
            String raw = response.headers().firstValue("Retry-After").orElse("").trim();
            if (raw.isEmpty()) {
                return 0;
            }
            long seconds = Long.parseLong(raw);
            if (seconds <= 0) {
                return 0;
            }
            return seconds * 1000L;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String parseMatrixError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject obj = parsed.getAsJsonObject();
            String errcode = getString(obj, "errcode");
            String error = getString(obj, "error");
            String combined = (errcode + " " + error).trim();
            return combined;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String parseErrcode(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            return parsed.isJsonObject() ? getString(parsed.getAsJsonObject(), "errcode") : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long parseRetryAfterMs(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return 0;
            }
            JsonObject obj = parsed.getAsJsonObject();
            JsonElement retry = obj.get("retry_after_ms");
            if (retry != null && retry.isJsonPrimitive()) {
                return retry.getAsLong();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * One page of {@code GET /rooms/{roomId}/messages} walking backwards from {@code from}
     * and stopping at {@code to} (a sync token, as the spec allows). Events are newest first.
     */
    public MessagesPage roomMessagesBackwards(String roomId, String from, String to, int limit, String filterJson)
            throws IOException, InterruptedException, MatrixException {
        String uri = roomPath(roomId) + "/messages?dir=b"
                + "&from=" + urlEncodeQuery(from)
                + "&to=" + urlEncodeQuery(to)
                + "&limit=" + limit
                + "&filter=" + urlEncodeQuery(filterJson);
        JsonObject response = getJson(uri, "messages");
        JsonElement chunk = response.get("chunk");
        return new MessagesPage(chunk != null && chunk.isJsonArray() ? chunk.getAsJsonArray() : new JsonArray(),
                getString(response, "end"));
    }

    public static final class MessagesPage {
        public final JsonArray chunk;
        /** Token for the next (older) page; empty when there is none. */
        public final String end;

        MessagesPage(JsonArray chunk, String end) {
            this.chunk = chunk;
            this.end = end == null ? "" : end;
        }
    }

    public static final class SyncResult {
        public final String nextBatch;
        public final JsonObject joinedRooms;

        public SyncResult(String nextBatch, JsonObject joinedRooms) {
            this.nextBatch = nextBatch == null ? "" : nextBatch;
            this.joinedRooms = joinedRooms == null ? new JsonObject() : joinedRooms;
        }
    }

    public static final class MatrixException extends Exception {
        public final int statusCode;
        public final long retryAfterMs;
        /** Matrix {@code errcode} of the error body; empty when the body was not a Matrix error. */
        public final String errcode;

        public MatrixException(String message, int statusCode, long retryAfterMs) {
            this(message, statusCode, retryAfterMs, "");
        }

        public MatrixException(String message, int statusCode, long retryAfterMs, String errcode) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
            this.errcode = errcode == null ? "" : errcode;
        }
    }

    private void cacheDisplayName(String key, String displayName, long nowMs) {
        synchronized (displayNameLock) {
            displayNameCache.put(key, new DisplayNameEntry(displayName == null ? "" : displayName, nowMs + DISPLAYNAME_TTL_MS));
        }
    }

    private static final class DisplayNameEntry {
        final String displayName;
        final long expiresAtMs;

        DisplayNameEntry(String displayName, long expiresAtMs) {
            this.displayName = displayName == null ? "" : displayName;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
