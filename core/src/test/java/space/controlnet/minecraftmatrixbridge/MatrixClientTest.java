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
