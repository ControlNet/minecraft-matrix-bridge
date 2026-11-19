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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BridgeServiceTest {
    @TempDir
    Path worldRoot;

    @Test
    void stopRetainsStateUntilBlockedBootstrapExits() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(), roomId, "token", true, false, true,
                    "[MC] ", "[Matrix] ", "!mc", 0, 20, 10, 64
            );

            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            AtomicBoolean blockFirstAnnouncement = new AtomicBoolean(true);
            McCallbacks callbacks = new McCallbacks() {
                @Override
                public void broadcast(String text) {
                }

                @Override
                public void announceMatrixConnected(String roomIdOrAlias) {
                    if (!blockFirstAnnouncement.getAndSet(false)) {
                        return;
                    }
                    callbackEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseCallback.await(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Deliberately model a callback that cannot stop immediately.
                        }
                    }
                }
            };

            BridgeService service = new BridgeService();
            service.start(settings, worldRoot, callbacks);
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

            service.stop();
            service.start(settings, worldRoot, callbacks);
            assertTrue(!service.isRunning(), "restart must wait for the previous lifecycle to exit");

            releaseCallback.countDown();
            waitUntil(() -> !service.isReady(), Duration.ofSeconds(1));
            service.stop();

            service.start(settings, worldRoot, callbacks);
            try {
                waitUntil(service::isReady, Duration.ofSeconds(2));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void workerThreadsAreDaemonSoACrashedServerCanExit() throws Exception {
        // Forge/NeoForge skip ServerStoppingEvent when the tick loop crashes; bridge
        // threads must not keep the JVM alive in that case.
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(), roomId, "token", true, true, false,
                    "[MC] ", "[Matrix] ", "!mc", 1000, 20, 10, 64
            );
            BridgeService service = new BridgeService();
            service.start(settings, worldRoot, text -> { });
            try {
                waitUntil(service::isReady, Duration.ofSeconds(2));
                List<String> names = new ArrayList<>();
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (thread.getName().startsWith("MatrixBridge-") && thread.isAlive()) {
                        names.add(thread.getName());
                        assertTrue(thread.isDaemon(), thread.getName() + " must be a daemon thread");
                    }
                }
                assertTrue(names.contains("MatrixBridge-Sender"), names.toString());
                assertTrue(names.contains("MatrixBridge-Sync"), names.toString());
            } finally {
                service.stop();
            }
        }
    }

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
                    "!mc",
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

                // The initial sync already wrote s0; s1 is saved just after its messages are handed over.
                waitUntil(() -> {
                    try {
                        return "s1".equals(stateOnDisk().get("since").getAsString());
                    } catch (Exception e) {
                        return false;
                    }
                }, Duration.ofSeconds(2));
                JsonObject state = stateOnDisk();
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
                    "!mc",
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
                    "!mc",
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

                waitUntil(() -> server.getSendRequests().size() >= 2, Duration.ofSeconds(2));
                assertEquals(server.getSendRequests().get(0).path(), server.getSendRequests().get(1).path(),
                        "Retries must preserve the transaction ID");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void botCommandsAreNotForwardedAndListRepliesInMatrix() throws Exception {
        String roomId = "!room:example.com";
        String selfUserId = "@bot:example.com";

        try (MockMatrixServer server = new MockMatrixServer("token", selfUserId)) {
            server.setJoinedRooms(List.of(roomId));
            server.setSendRateLimit(1, 1);

            // Initial catch-up sync to establish a since token.
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$cmd1", "@alice:example.com", "!mc list"));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            McCallbacks callbacks = new McCallbacks() {
                @Override
                public void broadcast(String text) {
                    received.add(text);
                }

                @Override
                public CompletableFuture<List<String>> getOnlinePlayerNames() {
                    return CompletableFuture.completedFuture(List.of("Alex", "Steve"));
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
                    "!mc",
                    0,
                    20,
                    100,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, callbacks);

                waitUntil(() -> server.getSendRequests().size() >= 2, Duration.ofSeconds(2));
                assertEquals(server.getSendRequests().get(0).path(), server.getSendRequests().get(1).path(),
                        "Bot reply retries must preserve the transaction ID");
                assertTrue(received.stream().noneMatch(s -> s.contains("!mc")), "should not forward bot command to Minecraft chat");

                String bodyJson = server.getSendRequests().get(0).body();
                JsonObject body = JsonParser.parseString(bodyJson).getAsJsonObject();
                assertEquals("Online players (2): Alex, Steve", body.get("body").getAsString());
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
                    "!mc",
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
                waitUntil(() -> !server.getSendRequests().isEmpty(), Duration.ofSeconds(2));

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
                    "!mc",
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

    @Test
    void retriesJoinedRoomsOnRateLimit() throws Exception {
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of("!room:example.com"));
            server.setJoinedRoomsRateLimit(1, 1);

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    "!room:example.com",
                    "token",
                    true,
                    false,
                    false,
                    "[MC] ",
                    "[Matrix] ",
                    "!mc",
                    0,
                    20,
                    10,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {});

                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertTrue(server.joinedRoomsCallCount() >= 2, "expected multiple joined_rooms calls after 429 retry");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void retriesJoinOnRateLimit() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of());
            server.setInvitedRooms(List.of(roomId));
            server.setJoinRateLimit(1, 1);
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomId,
                    "token",
                    false,
                    true,
                    false,
                    "[MC] ",
                    "[Matrix] ",
                    "!mc",
                    0,
                    20,
                    10,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {});

                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertTrue(server.joinCallCount() >= 2, "expected multiple join calls after 429 retry");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void queueSaturationDropsMessagesWithoutBlocking() throws Exception {
        String roomId = "!room:example.com";
        int maxQueueSize = 5;

        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));

            BridgeSettings settings = new BridgeSettings(
                    server.homeserverUrl(),
                    roomId,
                    "token",
                    true,
                    false,
                    false,
                    "[MC] ",
                    "[Matrix] ",
                    "!mc",
                    0,
                    20,
                    maxQueueSize,
                    64
            );

            BridgeService service = new BridgeService();
            try {
                service.start(settings, worldRoot, text -> {});

                waitUntil(service::isReady, Duration.ofSeconds(2));

                int successCount = 0;
                int failCount = 0;
                for (int i = 0; i < maxQueueSize + 10; i++) {
                    if (service.enqueueMcMessage("[MC] <Test> message" + i)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }

                assertTrue(successCount >= maxQueueSize, "expected at least maxQueueSize successful enqueues");
                assertTrue(failCount > 0, "expected some messages to be dropped after queue is full");
                assertEquals(maxQueueSize, service.getQueueSize());
            } finally {
                service.stop();
            }
        }
    }

    private BridgeSettings syncOnlySettings(MockMatrixServer server, String roomId) {
        return new BridgeSettings(server.homeserverUrl(), roomId, "token", false, true, false,
                "[MC] ", "[Matrix] ", "!mc", 0, 20, 100, 64);
    }

    private void writeState(JsonObject state) throws Exception {
        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        Files.createDirectories(statePath.getParent());
        Files.writeString(statePath, state.toString(), StandardCharsets.UTF_8);
    }

    private static String queryParam(String rawQuery, String name) {
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void rejectedSinceTokenFallsBackToAFreshSync() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_stale");
            writeState(state);
            // The homeserver no longer accepts the stored token (e.g. after a database reset).
            server.setSyncError(1, 400, "M_INVALID_PARAM");
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$fresh", "@alice:example.com", "after reset"));
            server.enqueueSyncResponse(roomId, "s1", events);

            CountDownLatch latch = new CountDownLatch(1);
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> {
                    if (text.endsWith("after reset")) {
                        latch.countDown();
                    }
                });
                assertTrue(latch.await(5, TimeUnit.SECONDS), "sync must recover from a rejected since token");
                List<MockMatrixServer.RecordedRequest> syncs = server.getSyncRequests();
                assertEquals("s_stale", queryParam(syncs.get(0).query(), "since"));
                assertEquals(null, queryParam(syncs.get(1).query(), "since"), "the retry must be a fresh initial sync");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void storedSinceFromAnotherAccountIsNotReused() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_other");
            state.addProperty("owner", "https://other.example|@bot:other.example");
            writeState(state);
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(() -> !server.getSyncRequests().isEmpty(), Duration.ofSeconds(2));
                assertEquals(null, queryParam(server.getSyncRequests().get(0).query(), "since"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void syncFilterOnlyRequestsTheRoomTimeline() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(() -> !server.getSyncRequests().isEmpty(), Duration.ofSeconds(2));
                JsonObject filter = JsonParser.parseString(
                        queryParam(server.getSyncRequests().get(0).query(), "filter")).getAsJsonObject();
                JsonObject room = filter.getAsJsonObject("room");
                for (JsonObject section : List.of(filter.getAsJsonObject("presence"), filter.getAsJsonObject("account_data"),
                        room.getAsJsonObject("ephemeral"), room.getAsJsonObject("account_data"), room.getAsJsonObject("state"))) {
                    assertEquals("[\"*\"]", section.getAsJsonArray("not_types").toString());
                }
                assertEquals("[\"" + roomId + "\"]", room.getAsJsonArray("rooms").toString());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void minecraftFormattingCodesAreStrippedFromMatrixMessages() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setRoomMemberDisplayName(roomId, "@alice:example.com", "§kAl§cice");
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$fmt", "@alice:example.com", "hi §lbold\u0007 §"));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> !received.isEmpty(), Duration.ofSeconds(2));
                assertEquals("[Matrix] <Alice> hi bold", received.get(0));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void eventCommandArgumentsHonourQuotes() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setStateContent(roomId, "m.room.power_levels", "",
                    JsonParser.parseString("{\"users\":{\"@admin:example.com\":100}}").getAsJsonObject());
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$c1", "@admin:example.com", "!mc event on PlayerLoggedInEvent \"\" 10m"));
            events.add(matrixTextEvent("$c2", "@admin:example.com", "!mc event on ServerChatEvent \"two words\" 10m"));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
            McCallbacks callbacks = new McCallbacks() {
                @Override
                public void broadcast(String text) {
                }

                @Override
                public CompletableFuture<String> handleEventTapCommand(String senderMxid, String[] args) {
                    commands.add(List.of(args));
                    return CompletableFuture.completedFuture("ok");
                }
            };
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, callbacks);
                waitUntil(() -> commands.size() >= 2, Duration.ofSeconds(2));
                assertEquals(List.of("on", "PlayerLoggedInEvent", "", "10m"), commands.get(0));
                assertEquals(List.of("on", "ServerChatEvent", "two words", "10m"), commands.get(1));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void commandTokenizerHandlesQuotesAndWhitespace() {
        assertEquals(List.of(), BridgeService.tokenizeCommand("   "));
        assertEquals(List.of("a", "b c", "", "d"), BridgeService.tokenizeCommand(" a  \"b c\" \"\"\td "));
        // An unterminated quote keeps the rest of the line as one argument.
        assertEquals(List.of("on", "x y"), BridgeService.tokenizeCommand("on \"x y"));
        // Mobile keyboards often turn straight quotes into curly ones.
        assertEquals(List.of("on", "two words"), BridgeService.tokenizeCommand("on \u201ctwo words\u201d"));
    }

    @Test
    void mcMessagesAreRejectedWhenMcToMatrixIsDisabled() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(service.isMcToMatrixDisabledByConfig());
                assertTrue(!service.enqueueMcMessage("[MC] would never be sent"),
                        "no sender thread runs, so queued messages would pile up silently");
                assertEquals(0, service.getQueueSize());
            } finally {
                service.stop();
            }
        }
    }

    private BridgeSettings sendOnlySettings(String homeserver, String roomId, int maxQueueSize) {
        return new BridgeSettings(homeserver, roomId, "token", true, false, false,
                "[MC] ", "[Matrix] ", "!mc", 0, 20, maxQueueSize, 64);
    }

    private static String sentBody(MockMatrixServer.RecordedRequest request) {
        return JsonParser.parseString(request.body()).getAsJsonObject().get("body").getAsString();
    }

    @Test
    void tapMessagesCannotTakeMoreThanHalfTheQueue() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            // Keep bootstrap (and so the sender) waiting, so nothing drains the queue.
            server.setWhoamiRateLimit(1, 10_000);
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(server.homeserverUrl(), roomId, 10), worldRoot, text -> { });
                int taps = 0;
                for (int i = 0; i < 20; i++) {
                    if (service.enqueueTapMessage("[EVT] tap " + i)) taps++;
                }
                int chats = 0;
                for (int i = 0; i < 20; i++) {
                    if (service.enqueueMcMessage("[MC] <Steve> chat " + i)) chats++;
                }
                assertEquals(5, taps, "event taps may use at most half of maxQueueSize");
                assertEquals(5, chats, "player chat keeps the other half");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void queuedLinesAreBatchedIntoFewMatrixMessages() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            // Bootstrap waits briefly, so all lines are queued before the sender starts.
            server.setWhoamiRateLimit(1, 500);
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(server.homeserverUrl(), roomId, 100), worldRoot, text -> { });
                int lines = BridgeService.MAX_BATCH_LINES + 5;
                for (int i = 0; i < lines; i++) {
                    assertTrue(service.enqueueMcMessage("[MC] <Steve> line " + i));
                }
                waitUntil(() -> server.getSendRequests().size() >= 2, Duration.ofSeconds(5));
                Thread.sleep(200);
                List<MockMatrixServer.RecordedRequest> sends = server.getSendRequests();
                assertEquals(2, sends.size(), "lines queued together must share Matrix events");
                String[] first = sentBody(sends.get(0)).split("\n");
                assertEquals(BridgeService.MAX_BATCH_LINES, first.length);
                assertEquals("[MC] <Steve> line 0", first[0]);
                assertEquals("[MC] <Steve> line " + (lines - 1), sentBody(sends.get(1)).split("\n")[4]);
            } finally {
                service.stop();
            }
        }
    }

    private static JsonObject matrixEvent(String eventId, String sender, String contentJson) {
        JsonObject evt = new JsonObject();
        evt.addProperty("type", "m.room.message");
        evt.addProperty("event_id", eventId);
        evt.addProperty("sender", sender);
        evt.add("content", JsonParser.parseString(contentJson).getAsJsonObject());
        return evt;
    }

    @Test
    void editsRepliesAndLongMessagesAreRelayedReadably() throws Exception {
        String roomId = "!room:example.com";
        String alice = "@alice:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setRoomMemberDisplayName(roomId, alice, "Alice");
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            JsonArray events = new JsonArray();
            events.add(matrixEvent("$edit", alice, "{\"msgtype\":\"m.text\",\"body\":\"* fixed typo\","
                    + "\"m.new_content\":{\"msgtype\":\"m.text\",\"body\":\"fixed typo\"},"
                    + "\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$orig\"}}"));
            events.add(matrixEvent("$editcmd", alice, "{\"msgtype\":\"m.text\",\"body\":\"* !mc list\","
                    + "\"m.new_content\":{\"msgtype\":\"m.text\",\"body\":\"!mc list\"},"
                    + "\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$cmd\"}}"));
            events.add(matrixEvent("$reply", alice, "{\"msgtype\":\"m.text\","
                    + "\"body\":\"> <@bob:example.com> original\\n> second line\\n\\nmy answer\","
                    + "\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"$x\"}}}"));
            events.add(matrixTextEvent("$long", alice, "x".repeat(5_000)));
            server.enqueueSyncResponse(roomId, "s1", events);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 3, Duration.ofSeconds(3));
                Thread.sleep(200);
                assertEquals(3, received.size(), "an edited bot command is neither run nor relayed: " + received);
                assertEquals("[Matrix] <Alice> fixed typo (edited)", received.get(0));
                assertEquals("[Matrix] <Alice> my answer", received.get(1));
                String longLine = received.get(2);
                assertTrue(longLine.endsWith("..."), longLine);
                assertEquals("[Matrix] <Alice> ".length() + BridgeService.MAX_MATRIX_TO_MC_CHARS, longLine.length());
                assertEquals(0, server.sendCallCount(), "the edited command must not be answered");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void discoversTheClientApiFromWellKnownWhenTheServerNameIsConfigured() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer api = new MockMatrixServer("token", "@bot:example.com");
             MockMatrixServer site = new MockMatrixServer("token", "@bot:example.com")) {
            api.setJoinedRooms(List.of(roomId));
            site.setWhoamiNotFound(true);
            site.setWellKnownBaseUrl(api.homeserverUrl());
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(site.homeserverUrl(), roomId, 10), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertTrue(service.enqueueMcMessage("[MC] <Steve> via well-known"));
                waitUntil(() -> api.sendCallCount() >= 1, Duration.ofSeconds(3));
                assertEquals(0, site.sendCallCount());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void wellKnownDiscoveryIsRetriedAfterAFailedLookup() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer api = new MockMatrixServer("token", "@bot:example.com");
             MockMatrixServer site = new MockMatrixServer("token", "@bot:example.com")) {
            api.setJoinedRooms(List.of(roomId));
            site.setWhoamiNotFound(true);
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(site.homeserverUrl(), roomId, 10), worldRoot, text -> { });
                // The delegation is not served yet (e.g. the web server is still starting).
                waitUntil(() -> site.wellKnownCallCount() >= 1, Duration.ofSeconds(3));
                site.setWellKnownBaseUrl(api.homeserverUrl());
                waitUntil(service::isReady, Duration.ofSeconds(8));
                assertTrue(api.whoamiCallCount() >= 1);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void followsAWellKnownRedirectToFindTheClientApi() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer api = new MockMatrixServer("token", "@bot:example.com");
             MockMatrixServer site = new MockMatrixServer("token", "@bot:example.com")) {
            api.setJoinedRooms(List.of(roomId));
            api.setWellKnownBaseUrl(api.homeserverUrl());
            site.setWhoamiNotFound(true);
            // Like an apex domain redirecting to www, which serves the delegation.
            site.setWellKnownRedirect(api.homeserverUrl() + "/.well-known/matrix/client");
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(site.homeserverUrl(), roomId, 10), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertTrue(service.isReady());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void keepsRetryingWithOneHintWhileTheHomeserverUrlAnswers404() throws Exception {
        String roomId = "!room:example.com";
        List<String> severe = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    severe.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("MatrixBridge");
        logger.addHandler(capture);
        // A wrong URL and a reverse proxy without a route yet (Synapse still starting) look
        // the same, so a 404 must not stop the bridge for good.
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setWhoamiNotFound(true);
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(server.homeserverUrl(), roomId, 10), worldRoot, text -> { });
                waitUntil(() -> server.whoamiCallCount() >= 2, Duration.ofSeconds(4));
                assertTrue(service.isRunning());
                assertTrue(server.whoamiCallCount() <= 3, "retries must back off, not spin");
                assertEquals(1, severe.stream().filter(m -> m.contains("404")).count(), String.valueOf(severe));

                server.setWhoamiNotFound(false);
                waitUntil(service::isReady, Duration.ofSeconds(6));
                assertTrue(service.isReady());
                assertEquals(1, severe.stream().filter(m -> m.contains("404")).count(), "the hint is logged once");
            } finally {
                service.stop();
                logger.removeHandler(capture);
            }
        }
    }

    @Test
    void transientHttpErrorsKeepTheSinceToken() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_keep");
            writeState(state);
            // A proxy timeout carries no Matrix errcode and says nothing about the token.
            server.setSyncError(1, 408, "");
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$kept", "@alice:example.com", "still here"));
            server.enqueueSyncResponse(roomId, "s1", events);

            CountDownLatch latch = new CountDownLatch(1);
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> {
                    if (text.endsWith("still here")) {
                        latch.countDown();
                    }
                });
                assertTrue(latch.await(5, TimeUnit.SECONDS), "sync must retry with the stored token");
                List<MockMatrixServer.RecordedRequest> syncs = server.getSyncRequests();
                assertEquals("s_keep", queryParam(syncs.get(0).query(), "since"));
                assertEquals("s_keep", queryParam(syncs.get(1).query(), "since"));
            } finally {
                service.stop();
            }
        }
    }

    private JsonObject stateOnDisk() throws Exception {
        Path statePath = worldRoot.resolve("data").resolve("minecraftmatrixbridge").resolve("state.json");
        return JsonParser.parseString(Files.readString(statePath, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void sinceIsSavedOnlyAfterItsMessagesWereForwarded() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$a", "@alice:example.com", "first"));
            events.add(matrixTextEvent("$b", "@alice:example.com", "second"));
            server.enqueueSyncResponse(roomId, "s1", events);

            CountDownLatch inFirst = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> {
                    if (text.endsWith("first")) {
                        inFirst.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    received.add(text);
                });
                assertTrue(inFirst.await(3, TimeUnit.SECONDS));
                // "second" is not delivered yet: a stop now must replay this batch next time.
                assertEquals("s_old", stateOnDisk().get("since").getAsString());
                release.countDown();
                waitUntil(() -> received.stream().anyMatch(t -> t.endsWith("second")), Duration.ofSeconds(3));
                waitUntil(() -> {
                    try {
                        return "s1".equals(stateOnDisk().get("since").getAsString());
                    } catch (Exception e) {
                        return false;
                    }
                }, Duration.ofSeconds(3));
            } finally {
                release.countDown();
                service.stop();
            }
        }
    }

    @Test
    void rateLimitedBotRepliesDoNotHoldUpMatrixChat() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            server.setSendRateLimit(1, 10_000);
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$cmd", "@alice:example.com", "!mc help"));
            events.add(matrixTextEvent("$chat", "@alice:example.com", "right after"));
            server.enqueueSyncResponse(roomId, "s1", events);

            CountDownLatch latch = new CountDownLatch(1);
            BridgeService service = new BridgeService();
            try {
                // MC -> Matrix is off, yet the bot must still be able to reply.
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> {
                    if (text.endsWith("right after")) {
                        latch.countDown();
                    }
                });
                assertTrue(latch.await(3, TimeUnit.SECONDS), "chat must not wait for a rate-limited bot reply");
                waitUntil(() -> server.sendCallCount() >= 1, Duration.ofSeconds(2));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void aSyncTokenFromAnotherRoomIsNotReused() throws Exception {
        String roomId = "!new:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("owner", server.homeserverUrl() + "|@bot:example.com|!old:example.com");
            state.addProperty("since", "s_old_room");
            writeState(state);
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertEquals(null, queryParam(server.getSyncRequests().get(0).query(), "since"),
                        "a new room starts with a fresh initial sync");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void aBotReplyThatKeepsFailingIsLoggedAndDoesNotDelayTheNext() throws Exception {
        String roomId = "!room:example.com";
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger("MatrixBridge");
        logger.addHandler(capture);
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            server.setSendError(3, 503);
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$c1", "@alice:example.com", "!mc help"));
            events.add(matrixTextEvent("$c2", "@alice:example.com", "!mc help"));
            server.enqueueSyncResponse(roomId, "s1", events);
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(() -> server.getSendRequests().size() >= 4, Duration.ofSeconds(8));
                assertTrue(warnings.stream().anyMatch(m -> m.contains("bot reply") && m.contains("dropping")),
                        String.valueOf(warnings));
            } finally {
                service.stop();
                logger.removeHandler(capture);
            }
        }
    }

    @Test
    void connectedNoticeWaitsForTheInitialSync() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setSyncRateLimit(1, 1_000);
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                BridgeSettings announcing = new BridgeSettings(server.homeserverUrl(), roomId, "token", false, true, true,
                        "[MC] ", "[Matrix] ", "!mc", 0, 20, 100, 64);
                service.start(announcing, worldRoot, received::add);
                waitUntil(() -> !server.getSyncRequests().isEmpty(), Duration.ofSeconds(2));
                Thread.sleep(200);
                // Messages sent now would land in the skipped initial sync, so do not claim readiness yet.
                assertTrue(!service.isReady(), "ready before the initial sync finished");
                assertTrue(received.isEmpty(), String.valueOf(received));
                waitUntil(service::isReady, Duration.ofSeconds(4));
                waitUntil(() -> received.stream().anyMatch(t -> t.startsWith("Matrix room connected:")), Duration.ofSeconds(2));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void limitedTimelinesAreBackfilledInOrder() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$4", "@alice:example.com", "four"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p_gap");
            // Backwards pages from the gap down to s_old, newest first. "$4" overlaps on purpose.
            JsonArray page1 = new JsonArray();
            page1.add(matrixTextEvent("$4", "@alice:example.com", "four"));
            page1.add(matrixTextEvent("$3", "@alice:example.com", "three"));
            page1.add(matrixTextEvent("$2", "@alice:example.com", "two"));
            server.setMessagesPage("p_gap", page1, "p_more");
            JsonArray page2 = new JsonArray();
            page2.add(matrixTextEvent("$1", "@alice:example.com", "one"));
            server.setMessagesPage("p_more", page2, "");

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 4, Duration.ofSeconds(3));
                Thread.sleep(200);
                assertEquals(List.of("[Matrix] <@alice:example.com> one", "[Matrix] <@alice:example.com> two",
                        "[Matrix] <@alice:example.com> three", "[Matrix] <@alice:example.com> four"), received);
                MockMatrixServer.RecordedRequest first = server.getMessagesRequests().get(0);
                assertEquals("b", queryParam(first.query(), "dir"));
                assertEquals("s_old", queryParam(first.query(), "to"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void invisibleFormattingCharactersAreStripped() {
        assertEquals("abc", BridgeService.sanitizeForMinecraft("a‮b​c﻿"));
        assertEquals("a b", BridgeService.sanitizeForMinecraft("a⁦ b⁩"));
    }

    @Test
    void matrixEventCommandsDefaultToRoomAdmins() {
        BridgeSettings defaults = new BridgeSettings("https://hs", "!r:hs", "t", true, true, true,
                "[MC] ", "[Matrix] ", "!mc", 0, 20, 10, 64);
        assertEquals(100, defaults.eventCommandMinPowerLevel);
        BridgeSettings invalid = new BridgeSettings("https://hs", "!r:hs", "t", true, true, true,
                "[MC] ", "[Matrix] ", "!mc", 0, 20, 10, 64, true, 10, 1000, -1);
        assertEquals(100, invalid.eventCommandMinPowerLevel);
    }

    @Test
    void playerNoticesUseTheSettingsTheBridgeStartedWith() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            // Bootstrap waits briefly, so all lines are queued before the sender starts.
            server.setWhoamiRateLimit(1, 500);
            BridgeService service = new BridgeService();
            try {
                // Join/leave off, lifecycle on.
                BridgeSettings settings = new BridgeSettings(server.homeserverUrl(), roomId, "token", true, false, false,
                        "[S] ", "[Matrix] ", "!mc", 0, 20, 100, 64, true, 10, 1000, 100, false, true);
                service.start(settings, worldRoot, text -> { });
                assertTrue(service.enqueueServerLifecycle(true));
                assertTrue(service.enqueuePlayerChat("Steve", "hi"));
                assertTrue(!service.enqueuePlayerJoinLeave("Steve", true), "join/leave notices are off");
                assertTrue(service.enqueuePlayerTestMessage("ping"));
                // A chat line whose format another mod replaced is relayed as players see it.
                assertTrue(service.enqueueShownChatLine("[Admin] Steve: hi"));
                waitUntil(() -> !server.getSendRequests().isEmpty(), Duration.ofSeconds(3));
                Thread.sleep(200);
                assertEquals("[S] * Server started.\n[S] <Steve> hi\n[S] [TEST] ping\n[S] [Admin] Steve: hi",
                        sentBody(server.getSendRequests().get(0)));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void playerNoticesAreRefusedWhenTheBridgeStartedWithoutMcToMatrix() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(2));
                assertTrue(!service.enqueuePlayerChat("Steve", "hi"));
                assertTrue(!service.enqueuePlayerJoinLeave("Steve", false));
                assertTrue(!service.enqueueServerLifecycle(false));
                assertTrue(!service.enqueuePlayerTestMessage("ping"));
                assertTrue(!service.enqueueShownChatLine("[Admin] Steve: hi"));
                assertEquals(0, service.getQueueSize());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void connectedNoticeRoomFollowsTheStartupSettings() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            server.enqueueSyncResponse(roomId, "s1", new JsonArray());
            BridgeService quiet = new BridgeService();
            try {
                quiet.start(syncOnlySettings(server, roomId), worldRoot, text -> { });
                waitUntil(quiet::isReady, Duration.ofSeconds(2));
                assertEquals(null, quiet.connectedNoticeRoom(), "announceConnected was off at startup");
            } finally {
                quiet.stop();
            }
            BridgeService announcing = new BridgeService();
            try {
                assertEquals(null, announcing.connectedNoticeRoom(), "not started");
                announcing.start(new BridgeSettings(server.homeserverUrl(), roomId, "token", false, true, true,
                        "[MC] ", "[Matrix] ", "!mc", 0, 20, 100, 64), worldRoot, text -> { });
                waitUntil(announcing::isReady, Duration.ofSeconds(2));
                assertEquals(roomId, announcing.connectedNoticeRoom());
            } finally {
                announcing.stop();
            }
        }
    }

    @Test
    void backfillContinuesPastAnEmptyPage() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$2", "@alice:example.com", "two"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p_gap");
            // Servers may return an empty page (e.g. after visibility filtering) that still points further back.
            server.setMessagesPage("p_gap", new JsonArray(), "p_more");
            JsonArray older = new JsonArray();
            older.add(matrixTextEvent("$1", "@alice:example.com", "one"));
            server.setMessagesPage("p_more", older, "");

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 2, Duration.ofSeconds(3));
                assertEquals(List.of("[Matrix] <@alice:example.com> one", "[Matrix] <@alice:example.com> two"), received);
                assertEquals(2, server.getMessagesRequests().size());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void onlyARealReplyFallbackIsStripped() {
        assertEquals("my answer", BridgeService.stripReplyFallback("> <@bob:example.com> original\n> more\n\nmy answer"));
        assertEquals("my answer", BridgeService.stripReplyFallback("> * <@bob:example.com> waves\n\nmy answer"));
        // Clients following Matrix v1.13 send replies without a fallback; a quote the user typed stays.
        assertEquals("> 5 minutes", BridgeService.stripReplyFallback("> 5 minutes"));
        assertEquals("> quoted\nanswer", BridgeService.stripReplyFallback("> quoted\nanswer"));
        assertEquals("> <dependency> is optional", BridgeService.stripReplyFallback("> <dependency> is optional"));
        // A fallback is always followed by a blank line and the reply itself.
        assertEquals("> <@bob:example.com> only a quote", BridgeService.stripReplyFallback("> <@bob:example.com> only a quote"));
        // A hand-written quote that only looks like the start of a user ID stays.
        assertEquals("> <@note\n\nsecond", BridgeService.stripReplyFallback("> <@note\n\nsecond"));
        assertEquals("my answer", BridgeService.stripReplyFallback("> <@bob:example.com> original\r\n\r\nmy answer"));
    }

    @Test
    void storedSyncTokenIsConfirmedBeforeAnnouncingConnected() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_stale");
            writeState(state);
            server.setSyncError(1, 400, "M_INVALID_PARAM");
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            server.enqueueSyncResponse(roomId, "s1", new JsonArray());

            List<Integer> syncsAtNotice = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                BridgeSettings announcing = new BridgeSettings(server.homeserverUrl(), roomId, "token", false, true, true,
                        "[MC] ", "[Matrix] ", "!mc", 30_000, 20, 100, 64);
                service.start(announcing, worldRoot, text -> {
                    if (text.startsWith("Matrix room connected:")) {
                        syncsAtNotice.add(server.getSyncRequests().size());
                    }
                });
                waitUntil(() -> !syncsAtNotice.isEmpty(), Duration.ofSeconds(5));
                // Chat sent after the notice must not fall into the fresh initial sync that replaces a rejected token.
                assertTrue(syncsAtNotice.get(0) >= 2, "connected before the stored token was checked: " + syncsAtNotice);
                // Checking the stored token must not hold the notice for a whole long poll.
                assertEquals("0", queryParam(server.getSyncRequests().get(0).query(), "timeout"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void aConfiguredAliasIsJoinedByAlias() throws Exception {
        String roomAlias = "#public:remote.example";
        String roomId = "!remote:remote.example";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setRoomAliasMapping(roomAlias, roomId);
            // Joining by ID alone fails when the homeserver does not know a server in the room yet.
            server.setJoinableAlias(roomAlias, roomId);
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            BridgeService service = new BridgeService();
            try {
                service.start(new BridgeSettings(server.homeserverUrl(), roomAlias, "token", false, true, false,
                        "[MC] ", "[Matrix] ", "!mc", 0, 20, 100, 64), worldRoot, text -> { });
                waitUntil(service::isReady, Duration.ofSeconds(3));
                assertEquals("/_matrix/client/v3/join/" + roomAlias,
                        java.net.URLDecoder.decode(server.getJoinRequests().get(0).path(), StandardCharsets.UTF_8));
                assertEquals(roomId, service.getResolvedRoomId());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void aTimedOutBackfillIsRetried() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$2", "@alice:example.com", "two"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p_gap");
            JsonArray older = new JsonArray();
            older.add(matrixTextEvent("$1", "@alice:example.com", "one"));
            server.setMessagesPage("p_gap", older, "");
            server.setMessagesError(1, 408);

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 2, Duration.ofSeconds(5));
                assertEquals(List.of("[Matrix] <@alice:example.com> one", "[Matrix] <@alice:example.com> two"), received);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void anUnrecognizedEndpointDoesNotDiscardTheSyncToken() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_keep");
            writeState(state);
            // E.g. a request routed to a worker that does not serve /sync during a restart.
            server.setSyncError(1, 404, "M_UNRECOGNIZED");
            JsonArray events = new JsonArray();
            events.add(matrixTextEvent("$kept", "@alice:example.com", "still here"));
            server.enqueueSyncResponse(roomId, "s1", events);

            CountDownLatch latch = new CountDownLatch(1);
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, text -> {
                    if (text.endsWith("still here")) {
                        latch.countDown();
                    }
                });
                assertTrue(latch.await(5, TimeUnit.SECONDS), "the message after the stored token must arrive");
                assertEquals("s_keep", queryParam(server.getSyncRequests().get(1).query(), "since"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void backfillIsBoundedByMessagesNotByRequests() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$2", "@alice:example.com", "two"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p0");
            // Ten empty pages, each pointing further back, before the one missed message.
            for (int i = 0; i < 10; i++) {
                server.setMessagesPage("p" + i, new JsonArray(), "p" + (i + 1));
            }
            JsonArray older = new JsonArray();
            older.add(matrixTextEvent("$1", "@alice:example.com", "one"));
            server.setMessagesPage("p10", older, "");

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 2, Duration.ofSeconds(5));
                assertEquals(List.of("[Matrix] <@alice:example.com> one", "[Matrix] <@alice:example.com> two"), received);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void backfillStopsAtExactlyTheMessageCap() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$live", "@alice:example.com", "live"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p0");
            // 99 messages, then full pages: a whole next page would overshoot the cap.
            int id = 0;
            for (int page = 0; page < 12; page++) {
                JsonArray chunk = new JsonArray();
                for (int i = 0; i < (page == 0 ? 99 : 100); i++, id++) {
                    chunk.add(matrixTextEvent("$h" + id, "@alice:example.com", "old " + id));
                }
                server.setMessagesPage("p" + page, chunk, "p" + (page + 1));
            }

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> new ArrayList<>(received).stream().anyMatch(t -> t.endsWith("live")), Duration.ofSeconds(10));
                Thread.sleep(200);
                assertEquals(1001, received.size(), "1000 missed messages plus the live one");
                List<MockMatrixServer.RecordedRequest> pages = server.getMessagesRequests();
                assertEquals("1", queryParam(pages.get(pages.size() - 1).query(), "limit"));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void anUnrecognizedMessagesEndpointIsRetried() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            JsonObject state = new JsonObject();
            state.addProperty("since", "s_old");
            writeState(state);
            JsonArray newest = new JsonArray();
            newest.add(matrixTextEvent("$2", "@alice:example.com", "two"));
            server.enqueueSyncResponse(roomId, "s1", newest, true, "p_gap");
            JsonArray older = new JsonArray();
            older.add(matrixTextEvent("$1", "@alice:example.com", "one"));
            server.setMessagesPage("p_gap", older, "");
            server.setMessagesError(1, 404, "M_UNRECOGNIZED");

            List<String> received = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(syncOnlySettings(server, roomId), worldRoot, received::add);
                waitUntil(() -> received.size() >= 2, Duration.ofSeconds(5));
                assertEquals(List.of("[Matrix] <@alice:example.com> one", "[Matrix] <@alice:example.com> two"), received);
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void aTimedOutSendIsRetriedWithTheSameTransaction() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.setSendError(1, 408);
            BridgeService service = new BridgeService();
            try {
                service.start(sendOnlySettings(server.homeserverUrl(), roomId, 100), worldRoot, text -> { });
                assertTrue(service.enqueueMcMessage("[MC] <Steve> first"));
                waitUntil(() -> server.getSendRequests().size() >= 2, Duration.ofSeconds(5));
                List<MockMatrixServer.RecordedRequest> sends = server.getSendRequests();
                assertEquals("[MC] <Steve> first", sentBody(sends.get(1)));
                assertEquals(sends.get(0).path(), sends.get(1).path(), "a retry must reuse the transaction ID");
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void notReadyWhileARejectedTokenIsReplaced() throws Exception {
        String roomId = "!room:example.com";
        try (MockMatrixServer server = new MockMatrixServer("token", "@bot:example.com")) {
            server.setJoinedRooms(List.of(roomId));
            server.enqueueSyncResponse(roomId, "s0", new JsonArray());
            List<String> notices = Collections.synchronizedList(new ArrayList<>());
            BridgeService service = new BridgeService();
            try {
                service.start(new BridgeSettings(server.homeserverUrl(), roomId, "token", false, true, true,
                        "[MC] ", "[Matrix] ", "!mc", 0, 20, 100, 64), worldRoot, text -> {
                    if (text.startsWith("Matrix room connected:")) {
                        notices.add(text);
                    }
                });
                waitUntil(service::isReady, Duration.ofSeconds(3));
                waitUntil(() -> notices.size() == 1, Duration.ofSeconds(2));
                // The running token gets rejected, and the fresh initial sync is held up by rate limits.
                server.setSyncRateLimit(2, 1_500);
                server.setSyncError(1, 400, "M_INVALID_PARAM");
                waitUntil(() -> server.pendingSyncErrors() == 0, Duration.ofSeconds(3));
                Thread.sleep(200);
                assertTrue(!service.isReady(), "messages sent now would be skipped by the fresh sync");
                assertEquals(null, service.connectedNoticeRoom());
                waitUntil(service::isReady, Duration.ofSeconds(6));
                // Players who joined meanwhile, or whose notice was dropped, are told again.
                waitUntil(() -> notices.size() == 2, Duration.ofSeconds(2));
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void anEmptyMcToMatrixPrefixIsKept() {
        BridgeSettings settings = new BridgeSettings("https://hs", "!r:hs", "t", true, true, true,
                "", "[Matrix] ", "!mc", 0, 20, 10, 64);
        assertEquals("", settings.mcToMatrixPrefix);
        assertTrue(!settings.enableJoinLeaveToMatrix, "matches the config default");
        assertTrue(!settings.enableServerLifecycleToMatrix, "matches the config default");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
