package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import space.controlnet.minecraftmatrixbridge.testutil.MockMatrixServer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MatrixClientTest {
    @Test
    void explicitTransactionIdIsRetainedAcrossRetries() throws Exception {
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setSendRateLimit(1, 1);
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            String transactionId = java.util.UUID.randomUUID().toString();
            org.junit.jupiter.api.Assertions.assertThrows(MatrixClient.MatrixException.class,
                    () -> client.sendText("!room:example.com", "Hello", transactionId));
            client.sendText("!room:example.com", "Hello", transactionId);
            assertEquals(server.getSendRequests().get(0).path(), server.getSendRequests().get(1).path());
        }
    }

    @Test
    void distinctMessagesUseUniqueUuidTransactions() throws Exception {
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var sends = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 64; i++) {
                    sends.add(executor.submit(() -> {
                        client.sendText("!room:example.com", "Hello");
                        return null;
                    }));
                }
                for (var send : sends) send.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            Set<String> ids = new java.util.HashSet<>();
            for (var request : server.getSendRequests()) {
                String id = request.path().substring(request.path().lastIndexOf('/') + 1);
                java.util.UUID.fromString(id);
                assertTrue(ids.add(id));
            }
            assertEquals(64, ids.size());
        }
    }

    @Test
    void stalledRequestsTimeOutIncludingSync() throws Exception {
        // Loopback-only fault injection; no real homeserver or credentials are used.
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> exchange.getRequestBody().readAllBytes());
        server.start();
        try {
            var client = new MatrixClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-only",
                    java.time.Duration.ofMillis(150));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class, client::whoami);
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class, client::joinedRooms);
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.resolveRoomAlias("#room:example.com"));
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.joinRoom("!room:example.com"));
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.getUserPowerLevel("!room:example.com", "@user:example.com"));
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.getRoomMemberDisplayName("!room:example.com", "@user:example.com"));
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.sendText("!room:example.com", "Hello"));
                org.junit.jupiter.api.Assertions.assertThrows(java.net.http.HttpTimeoutException.class,
                        () -> client.syncOnce("", 100, ""));
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syncDeadlineIncludesLongPollAndNetworkAllowance() {
        var client = new MatrixClient("http://localhost", "test-only", java.time.Duration.ofSeconds(30));
        assertEquals(java.time.Duration.ofSeconds(60), client.syncRequestTimeout(30000));
        assertEquals(java.time.Duration.ofSeconds(30), client.syncRequestTimeout(0));
        assertEquals(java.time.Duration.ofSeconds(30), client.syncRequestTimeout(-1));
    }

    @Test
    void whoamiReturnsUserId() throws Exception {
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            assertEquals("@bot:example.com", client.whoami());
        }
    }

    @Test
    void sendTextSendsJsonBody() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            client.sendText(roomId, "Hello");

            List<MockMatrixServer.RecordedRequest> requests = server.getSendRequests();
            assertEquals(1, requests.size());

            MockMatrixServer.RecordedRequest req = requests.get(0);
            assertEquals("PUT", req.method());
            assertNotNull(req.contentType());
            assertTrue(req.contentType().startsWith("application/json"));

            String encodedRoomId = urlEncode(roomId);
            assertTrue(req.path().startsWith("/_matrix/client/v3/rooms/" + encodedRoomId + "/send/m.room.message/"));

            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            assertEquals("m.text", body.get("msgtype").getAsString());
            assertEquals("Hello", body.get("body").getAsString());
        }
    }

    @Test
    void syncOnceParsesJoinedRooms() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            JsonArray events = new JsonArray();
            server.enqueueSyncResponse(roomId, "b1", events);

            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            MatrixClient.SyncResult result = client.syncOnce("", 0, "{\"room\":{}}");
            assertEquals("b1", result.nextBatch);
            assertTrue(result.joinedRooms.has(roomId));
        }
    }

    @Test
    void resolveRoomAliasReturnsRoomId() throws Exception {
        String roomAlias = "#room:example.com";
        String roomId = "!roomid:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setRoomAliasMapping(roomAlias, roomId);

            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            assertEquals(roomId, client.resolveRoomAlias(roomAlias));

            List<MockMatrixServer.RecordedRequest> requests = server.getDirectoryRequests();
            assertEquals(1, requests.size());
            MockMatrixServer.RecordedRequest req = requests.get(0);
            assertEquals("GET", req.method());
            assertEquals("/_matrix/client/v3/directory/room/" + urlEncode(roomAlias), req.path());
        }
    }

    @Test
    void joinedRoomsParsesRoomIds() throws Exception {
        String r1 = "!room1:example.com";
        String r2 = "!room2:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(r1, r2));
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");

            Set<String> joined = client.joinedRooms();
            assertTrue(joined.contains(r1));
            assertTrue(joined.contains(r2));
        }
    }

    @Test
    void joinRoomAcceptsInvite() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setInvitedRooms(List.of(roomId));
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");

            assertEquals(roomId, client.joinRoom(roomId));
            assertTrue(client.joinedRooms().contains(roomId));
        }
    }

    @Test
    void getRoomMemberDisplayNameReadsMemberState() throws Exception {
        String roomId = "!room:example.com";
        String userId = "@alice:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setRoomMemberDisplayName(roomId, userId, "Alice");
            MatrixClient client = new MatrixClient(server.homeserverUrl(), "token");
            assertEquals("Alice", client.getRoomMemberDisplayName(roomId, userId));
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
