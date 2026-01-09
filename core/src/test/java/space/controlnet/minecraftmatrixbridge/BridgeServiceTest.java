package space.controlnet.minecraftmatrixbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.controlnet.minecraftmatrixbridge.testutil.MockMatrixServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BridgeServiceTest {
    @TempDir
    Path worldRoot;

    @Test
    void forwardsMatrixMessagesAndPersistsSince() throws Exception {
        String roomId = "!room:example.com";
        String selfUserId = "@bot:example.com";
        String expectedForwarded = "[Matrix] <Alice> hello world";

        try (MockMatrixServer server = new MockMatrixServer("token", selfUserId)) {
            server.setJoinedRooms(List.of(roomId));
            server.setRoomMemberDisplayName(roomId, "@alice:example.com", "Alice");
            // Initial catch-up sync to establish a since token.
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            // Next sync includes one self message (ignored) and one normal message.
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$self", selfUserId, "ignore"));
            events.add(matrixTextEvent("$e1", "@alice:example.com", "hello\nworld"));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(1);
            McCallbacks callbacks = text -> {
                received.add(text);
                if (expectedForwarded.equals(text)) {
                    latch.countDown();
                }
            };

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomId,
                    "token",
                    false,
                    true,
                    true,
                    "[MC] ",
                    "[Matrix] ",
                    0,
                    20,
                    100,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, callbacks);

                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected a forwarded Matrix message");
                assertTrue(received.stream().anyMatch(s -> s.startsWith("Matrix room connected:")), "expected a connected announcement");
                assertTrue(received.contains(expectedForwarded));

                Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
                waitUntil(() -> Files.exists(statePath), Duration.ofSeconds(2));

                String stateJson = Files.readString(statePath, StandardCharsets.UTF_8);
                JsonObject state = JsonParser.parseString(stateJson).getAsJsonObject();
                assertEquals("s1", state.get("since").getAsString());
                assertEquals(selfUserId, state.get("self_user_id").getAsString());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void retriesWhoamiOnRateLimit() throws Exception {
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of("!room:example.com"));
            server.setWhoamiRateLimit(1, 1);

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    "!room:example.com",
                    "token",
                    true,
                    false,
                    true,
                    "[MC] ",
                    "[Matrix] ",
                    0,
                    20,
                    10,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {
                });

                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(server.whoamiCallCount() >= 2);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void retriesSendOnRateLimit() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setSendRateLimit(1, 1);

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomId,
                    "token",
                    true,
                    false,
                    true,
                    "[MC] ",
                    "[Matrix] ",
                    0,
                    20,
                    10,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {
                });

                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(service.enqueueMcMessage("[MC] <Steve> hello"));

                waitUntil(() -> server.sendCallCount() >= 2, Duration.ofSeconds(2));
                assertTrue(server.getSendRequests().size() >= 2);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void resolvesRoomAliasAndUsesResolvedRoomId() throws Exception {
        String roomAlias = "#room:example.com";
        String roomId = "!roomid:example.com";
        String selfUserId = "@bot:example.com";
        String expectedForwarded = "[Matrix] <Alice> hello from alias";

        try (MockMatrixServer server = new MockMatrixServer("token", selfUserId)) {
            server.setRoomAliasMapping(roomAlias, roomId);
            server.setJoinedRooms(List.of(roomId));
            server.setRoomMemberDisplayName(roomId, "@alice:example.com", "Alice");

            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$e1", "@alice:example.com", "hello from alias"));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(1);
            McCallbacks callbacks = text -> {
                received.add(text);
                if (expectedForwarded.equals(text)) {
                    latch.countDown();
                }
            };

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomAlias,
                    "token",
                    true,
                    true,
                    true,
                    "[MC] ",
                    "[Matrix] ",
                    0,
                    20,
                    100,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, callbacks);

                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(server.resolveAliasCallCount() >= 1, "expected resolveRoomAlias call(s)");

                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected a forwarded Matrix message");
                assertTrue(received.stream().anyMatch(s -> s.startsWith("Matrix room connected:")), "expected a connected announcement");
                assertTrue(received.contains(expectedForwarded));

                assertTrue(service.enqueueMcMessage("[MC] <Steve> hello"));
                waitUntil(() -> server.sendCallCount() >= 1, Duration.ofSeconds(2));

                String encodedRoomId = urlEncode(roomId);
                assertTrue(server.getSendRequests().get(0).path().contains("/_matrix/client/v3/rooms/" + encodedRoomId + "/send/m.room.message/"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void acceptsInviteAndJoinsRoomOnStartup() throws Exception {
        String roomId = "!room:example.com";
        String selfUserId = "@bot:example.com";

        try (MockMatrixServer server = new MockMatrixServer("token", selfUserId)) {
            // Not joined initially, but invited.
            server.setJoinedRooms(List.of());
            server.setInvitedRooms(List.of(roomId));

            // Initial catch-up sync to establish a since token (after joining).
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomId,
                    "token",
                    false,
                    true,
                    true,
                    "[MC] ",
                    "[Matrix] ",
                    0,
                    20,
                    100,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {
                });

                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(server.joinCallCount() >= 1, "expected join call(s) to accept invite");
                assertTrue(server.joinedRoomsCallCount() >= 1);
            } finally {
                service.stop();
            }
        }
    }

    private static JsonObject matrixTextEvent(String eventId, String sender, String body) {
        JsonObject evt = new JsonObject();
        evt.addProperty("type", "m.room.message");
        evt.addProperty("event_id", eventId);
        evt.addProperty("sender", sender);
        JsonObject content = new JsonObject();
        content.addProperty("msgtype", "m.text");
        content.addProperty("body", body);
        evt.add("content", content);
        return evt;
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met before timeout " + timeout);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
