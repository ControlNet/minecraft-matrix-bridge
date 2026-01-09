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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class MatrixClient {
    private final HttpClient http;
    private final String homeserver;
    private final String accessToken;

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
        this.http = HttpClient.newHttpClient();
        this.homeserver = stripTrailingSlash(homeserver);
        this.accessToken = accessToken;
    }

    public String whoami() throws IOException, InterruptedException, MatrixException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(homeserver + "/_matrix/client/v3/account/whoami"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(homeserver + "/_matrix/client/v3/joined_rooms"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
        String txnId = Long.toString(System.currentTimeMillis());
        String uri = homeserver + "/_matrix/client/v3/rooms/" + urlEncodePath(roomId)
                + "/send/m.room.message/" + urlEncodePath(txnId);

        JsonObject body = new JsonObject();
        body.addProperty("msgtype", "m.text");
        body.addProperty("body", text == null ? "" : text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sb.toString()))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw toException("sync", response);
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String nextBatch = getString(root, "next_batch");
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
        return new MatrixException(msg, status, retryAfterMs);
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

        public MatrixException(String message, int statusCode, long retryAfterMs) {
            super(message);
            this.statusCode = statusCode;
            this.retryAfterMs = retryAfterMs;
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
