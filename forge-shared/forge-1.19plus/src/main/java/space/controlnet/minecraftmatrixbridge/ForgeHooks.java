package space.controlnet.minecraftmatrixbridge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;

import com.mojang.brigadier.arguments.StringArgumentType;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ForgeHooks {
    private static final ForgeMinecraftCompat MC = ForgeCommandCompat.minecraft();
    private static final ForgeEventBus EVENTS = ForgeCommandCompat.events();

    static void register() {
        EVENTS.register(new ForgeHooks());
    }
    static ModContainer getModContainer() {
        return ModList.get().getModContainerById(MatrixBridgeMod.MOD_ID)
                .orElseThrow(() -> new IllegalStateException("Missing mod container: " + MatrixBridgeMod.MOD_ID));
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_WAIT_TICKS = 100; // 5 seconds max wait for client settings
    // Default ClientInformation values (from ClientInformation.createDefault())
    private static final int DEFAULT_VIEW_DISTANCE = 2;
    private static final int DEFAULT_MODEL_CUSTOMISATION = 0;
    private static final ConcurrentHashMap<Class<?>, Method> VIEW_DISTANCE_METHODS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> MISSING_VIEW_DISTANCE_METHODS = ConcurrentHashMap.newKeySet();

    private BridgeService bridgeService;
    private McCallbacks callbacks;
    private EventTapManager eventTapManager;
    private volatile MinecraftServer runningServer;
    private volatile String connectedRoomIdOrAlias = "";
    private final ConcurrentHashMap<UUID, Integer> pendingConnectedNoticeTicks = new ConcurrentHashMap<>();

    private void closeEventTapManager() {
        EventTapManager manager = eventTapManager;
        eventTapManager = null;
        if (manager != null) {
            manager.close();
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        this.runningServer = server;
        Path worldRoot = MC.worldRoot(server);

        closeEventTapManager();
        if (bridgeService != null) {
            bridgeService.stop();
        }
        bridgeService = new BridgeService();
        callbacks = new McCallbacks() {
            @Override
            public void broadcast(String text) {
                MinecraftServer s = server;
                if (s == null) {
                    return;
                }
                s.execute(() -> MC.broadcast(s, text));
            }

                    @Override
	                    public void announceMatrixConnected(String roomIdOrAlias) {
	                        MinecraftServer s = server;
	                        if (s == null) {
	                            return;
	                        }
	                        String room = (roomIdOrAlias == null || roomIdOrAlias.isBlank()) ? "<unknown>" : roomIdOrAlias;
	                        connectedRoomIdOrAlias = room;
	                        s.execute(() -> {
	                            for (ServerPlayer p : MC.players(s)) {
	                                scheduleConnectedNotice(p);
	                            }
	                        });
	                    }

                        @Override
                        public CompletableFuture<List<String>> getOnlinePlayerNames() {
                            CompletableFuture<List<String>> fut = new CompletableFuture<>();
                            MinecraftServer s = server;
                            if (s == null) {
                                fut.complete(List.of());
                                return fut;
                            }
                            s.execute(() -> {
                                List<String> names = new ArrayList<>();
                                for (ServerPlayer p : MC.players(s)) {
                                    names.add(MC.playerName(p));
                                }
                                Collections.sort(names);
                                fut.complete(names);
                            });
                            return fut;
                        }

                        @Override
                        public CompletableFuture<String> handleEventTapCommand(String senderMxid, String[] args) {
                            CompletableFuture<String> fut = new CompletableFuture<>();
                            EventTapManager mgr = eventTapManager;
                            if (mgr == null) {
                                fut.complete("Event tap manager is not initialized.");
                                return fut;
                            }
                            MinecraftServer s = server;
                            if (s == null) {
                                fut.complete("Server is not available.");
                                return fut;
                            }
                            s.execute(() -> {
                                try {
                                    String result = mgr.handleCommand(args);
                                    fut.complete(result);
                                } catch (Exception e) {
                                    fut.complete("Error: " + e.getMessage());
                                }
                            });
                            return fut;
                        }
	                };
        BridgeSettings settings = loadSettings();
        bridgeService.start(settings, worldRoot, callbacks);

        if (settings.enableEventTaps) {
            eventTapManager = new EventTapManager(
                    bridgeService,
                    EVENTS,
                    settings.maxActiveEventTaps,
                    settings.defaultEventThrottleMs
            );
        }

        if (MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get() && MatrixBridgeConfig.ENABLE_SERVER_LIFECYCLE_TO_MATRIX.get()) {
            String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* Server started.";
            bridgeService.enqueueMcMessage(formatted);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BridgeService service = bridgeService;
        bridgeService = null;
        closeEventTapManager();
        runningServer = null;
        connectedRoomIdOrAlias = "";
        pendingConnectedNoticeTicks.clear();
        if (service != null) {
            if (MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get() && MatrixBridgeConfig.ENABLE_SERVER_LIFECYCLE_TO_MATRIX.get()) {
                String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* Server stopping.";
                service.enqueueMcMessage(formatted);
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(750);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    service.stop();
                }, "MatrixBridge-Stopper");
                t.start();
            } else {
                service.stop();
            }
        }
        callbacks = null;
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        var descriptor = FileWatcher.class.getModule().getDescriptor();
        String version = descriptor == null ? "" : descriptor.rawVersion().orElse("");
        ConfigWatcherShutdown.afterServerExit(MC.isDedicatedServer(event.getServer()), version,
                Thread.currentThread(), () -> {
                    try {
                        EVENTS.stopConfigWatcher();
                    } catch (Exception e) {
                        // Older NightConfig declares IOException; newer versions do not.
                        throw new IllegalStateException("Could not close NightConfig FileWatcher", e);
                    }
                });
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (bridgeService == null || !bridgeService.isRunning()) {
            return;
        }
        if (!MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()) {
            return;
        }

        String msg = event.getMessage().getString();
        if (msg == null || msg.isBlank()) {
            return;
        }

        String playerName = MC.playerName(event.getPlayer());
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "<" + playerName + "> " + msg;
        bridgeService.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        BridgeService service = bridgeService;

        if (service != null && service.isRunning()
                && MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()
                && MatrixBridgeConfig.ENABLE_JOIN_LEAVE_TO_MATRIX.get()) {
            String playerName = MC.playerName(event.getEntity());
            String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* " + playerName + " joined the game";
            service.enqueueMcMessage(formatted);
        }

        if (service == null || !service.isReady()) {
            return;
        }
        if (!MatrixBridgeConfig.ANNOUNCE_CONNECTED.get()) {
            return;
        }
        String configured = MatrixBridgeConfig.ROOM_ID.get();
        String roomId = (configured != null && configured.startsWith("#"))
                ? configured
                : service.getResolvedRoomId();
        String room = (roomId == null || roomId.isBlank()) ? "<unknown>" : roomId;
        connectedRoomIdOrAlias = room;
        scheduleConnectedNotice(event.getEntity());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        onServerPostTick();
    }

    void onServerPostTick() {
        MinecraftServer s = runningServer;
        if (s == null) {
            return;
        }
        if (pendingConnectedNoticeTicks.isEmpty()) {
            return;
        }

        String room = connectedRoomIdOrAlias;
        if (room == null || room.isBlank()) {
            room = "<unknown>";
        }

        for (Map.Entry<UUID, Integer> e : pendingConnectedNoticeTicks.entrySet()) {
            UUID id = e.getKey();
            int ticksWaited = (e.getValue() == null ? 0 : e.getValue()) + 1;

            ServerPlayer p = MC.player(s, id);
            if (p == null) {
                pendingConnectedNoticeTicks.remove(id);
                continue;
            }

            // Check if we should send the notice:
            // 1. Client settings appear to have been received (heuristic: non-default values), OR
            // 2. We've waited the maximum time (timeout - assume English or slow client)
            boolean hasClientSettings = hasReceivedClientSettings(p);
            boolean timeout = ticksWaited >= MAX_WAIT_TICKS;

            if (hasClientSettings || timeout) {
                pendingConnectedNoticeTicks.remove(id);
                String lang = getPlayerLanguage(p);
                String msg = Localizer.connected(lang, room);
                MC.sendPlayerMessage(p, MC.text(msg));
            } else {
                pendingConnectedNoticeTicks.put(id, ticksWaited);
            }
        }
    }

    /**
     * Heuristic to detect if the client has sent its settings packet.
     * Default ClientInformation has viewDistance=2 and modelCustomisation=0.
     * Most real clients will have different values (higher view distance, skin layers enabled).
     */
    private static boolean hasReceivedClientSettings(ServerPlayer player) {
        try {
            // Try to get view distance - most clients have > 2
            int viewDistance = getPlayerViewDistance(player);
            if (viewDistance != DEFAULT_VIEW_DISTANCE) {
                return true;
            }

            // Try to get model customisation (skin layers) - most clients have > 0
            int modelCustomisation = getPlayerModelCustomisation(player);
            if (modelCustomisation != DEFAULT_MODEL_CUSTOMISATION) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static int getPlayerViewDistance(ServerPlayer player) {
        Class<?> playerType = player.getClass();
        Method method = VIEW_DISTANCE_METHODS.get(playerType);
        if (method == null && !MISSING_VIEW_DISTANCE_METHODS.contains(playerType)) {
            method = findViewDistanceMethod(playerType);
            if (method == null) {
                MISSING_VIEW_DISTANCE_METHODS.add(playerType);
                return DEFAULT_VIEW_DISTANCE;
            }
            Method existing = VIEW_DISTANCE_METHODS.putIfAbsent(playerType, method);
            if (existing != null) {
                method = existing;
            }
        }
        if (method != null) {
            try {
                Object result = method.invoke(player);
                if (result instanceof Integer value) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                VIEW_DISTANCE_METHODS.remove(playerType, method);
                MISSING_VIEW_DISTANCE_METHODS.add(playerType);
            }
        }
        return DEFAULT_VIEW_DISTANCE;
    }

    private static Method findViewDistanceMethod(Class<?> playerType) {
        for (String methodName : new String[]{"requestedViewDistance", "getRequestedViewDistance"}) {
            try {
                return playerType.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                // Try the next version-specific name.
            }
        }
        return null;
    }

    private static int getPlayerModelCustomisation(ServerPlayer player) {
        String lang = getPlayerLanguage(player);
        if (!"en_us".equals(lang)) {
            return 1; // Non-default, so settings were received
        }
        return DEFAULT_MODEL_CUSTOMISATION;
    }

    private void scheduleConnectedNotice(Object player) {
        if (!(player instanceof ServerPlayer p)) {
            return;
        }
        // Start with 0 ticks waited
        pendingConnectedNoticeTicks.put(MC.playerId(p), 0);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BridgeService service = bridgeService;
        if (service == null || !service.isRunning()) {
            return;
        }
        if (!MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()) {
            return;
        }
        if (!MatrixBridgeConfig.ENABLE_JOIN_LEAVE_TO_MATRIX.get()) {
            return;
        }

        String playerName = MC.playerName(event.getEntity());
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* " + playerName + " left the game";
        service.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = MC.literal("matrix")
                .requires(source -> MC.hasPermission(source, 2))
                .then(MC.literal("status").executes(ctx -> {
                    if (bridgeService == null) {
                        MC.sendSuccess(ctx.getSource(), MC.text("MatrixBridge: not initialized."), false);
                        return 0;
                    }
                    String msg = "MatrixBridge: running=" + bridgeService.isRunning()
                            + ", ready=" + bridgeService.isReady()
                            + ", selfUserId=" + (bridgeService.getSelfUserId().isBlank() ? "<unknown>" : bridgeService.getSelfUserId())
                            + ", queue=" + bridgeService.getQueueSize();
                    MC.sendSuccess(ctx.getSource(), MC.text(msg), false);
                    return 1;
                }))
                .then(MC.literal("reload").executes(ctx -> {
                    MinecraftServer server = MC.server(ctx.getSource());
                    Path worldRoot = MC.worldRoot(server);

                    if (bridgeService != null) {
                        bridgeService.stop();
                    }
                    closeEventTapManager();
                    bridgeService = new BridgeService();
                    callbacks = new McCallbacks() {
                        @Override
                        public void broadcast(String text) {
                            MinecraftServer s = server;
                            if (s == null) {
                                return;
                            }
                            s.execute(() -> MC.broadcast(s, text));
                        }

                        @Override
                        public void announceMatrixConnected(String roomIdOrAlias) {
                            MinecraftServer s = server;
                            if (s == null) {
                                return;
                            }
                            String room = (roomIdOrAlias == null || roomIdOrAlias.isBlank()) ? "<unknown>" : roomIdOrAlias;
                            connectedRoomIdOrAlias = room;
                            s.execute(() -> {
                                for (ServerPlayer p : MC.players(s)) {
                                    scheduleConnectedNotice(p);
                                }
                            });
                        }

                        @Override
                        public CompletableFuture<List<String>> getOnlinePlayerNames() {
                            CompletableFuture<List<String>> fut = new CompletableFuture<>();
                            MinecraftServer s = server;
                            if (s == null) {
                                fut.complete(List.of());
                                return fut;
                            }
                            s.execute(() -> {
                                List<String> names = new ArrayList<>();
                                for (ServerPlayer p : MC.players(s)) {
                                    names.add(MC.playerName(p));
                                }
                                Collections.sort(names);
                                fut.complete(names);
                            });
                            return fut;
                        }

                        @Override
                        public CompletableFuture<String> handleEventTapCommand(String senderMxid, String[] args) {
                            CompletableFuture<String> fut = new CompletableFuture<>();
                            EventTapManager mgr = eventTapManager;
                            if (mgr == null) {
                                fut.complete("Event tap manager is not initialized.");
                                return fut;
                            }
                            MinecraftServer s = server;
                            if (s == null) {
                                fut.complete("Server is not available.");
                                return fut;
                            }
                            s.execute(() -> {
                                try {
                                    String result = mgr.handleCommand(args);
                                    fut.complete(result);
                                } catch (Exception e) {
                                    fut.complete("Error: " + e.getMessage());
                                }
                            });
                            return fut;
                        }
                    };
                    bridgeService.start(loadSettings(), worldRoot, callbacks);

                    // Recreate event tap manager if enabled
                    BridgeSettings settings = loadSettings();
                    if (settings.enableEventTaps) {
                        eventTapManager = new EventTapManager(
                                bridgeService,
                                EVENTS,
                                settings.maxActiveEventTaps,
                                settings.defaultEventThrottleMs
                        );
                    }

                    MC.sendSuccess(ctx.getSource(), MC.text("MatrixBridge reload requested."), true);
                    return 1;
                }))
                .then(MC.literal("test").executes(ctx -> {
                    if (bridgeService == null || !bridgeService.isRunning()) {
                        MC.sendFailure(ctx.getSource(), MC.text("MatrixBridge is not running."));
                        return 0;
                    }
                    if (!MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()) {
                        MC.sendFailure(ctx.getSource(), MC.text("MC → Matrix is disabled (enableMcToMatrix=false)."));
                        return 0;
                    }
                    String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "[TEST] " + Instant.now();
                    boolean queued = bridgeService.enqueueMcMessage(formatted);
                    if (queued) {
                        MC.sendSuccess(ctx.getSource(), MC.text("Queued test message."), false);
                        return 1;
                    }
                    MC.sendFailure(ctx.getSource(), MC.text("Failed to queue test message (queue full or bridge not ready)."));
                    return 0;
                }))
                .then(MC.literal("event")
                    .then(MC.literal("on")
                        .then(MC.argument("eventName", StringArgumentType.string())
                            .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), "", "once"))
                            .then(MC.argument("filter", StringArgumentType.string())
                                .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), StringArgumentType.getString(ctx, "filter"), "once"))
                                .then(MC.argument("duration", StringArgumentType.string())
                                    .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), StringArgumentType.getString(ctx, "filter"), StringArgumentType.getString(ctx, "duration")))))))
                    .then(MC.literal("off")
                        .then(MC.argument("eventName", StringArgumentType.string())
                            .executes(ctx -> eventOff(ctx.getSource(), StringArgumentType.getString(ctx, "eventName")))))
                    .then(MC.literal("list")
                        .executes(ctx -> eventList(ctx.getSource())))
                    .then(MC.literal("search")
                        .then(MC.argument("query", StringArgumentType.string())
                            .executes(ctx -> eventSearch(ctx.getSource(), StringArgumentType.getString(ctx, "query")))))
                    .then(MC.literal("help")
                        .executes(ctx -> eventHelp(ctx.getSource()))));

        event.getDispatcher().register(root);
        LOGGER.debug("Registered /matrix command.");
    }

    private static String getPlayerLanguage(Object player) {
        if (player == null) {
            return "en_us";
        }
        // Try a few known shapes across MC versions; fall back to en_us.
        try {
            Method m = player.getClass().getMethod("getLanguage");
            Object res = m.invoke(player);
            if (res instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        try {
            Method m = player.getClass().getMethod("clientInformation");
            Object info = m.invoke(player);
            if (info != null) {
                Method m2 = info.getClass().getMethod("language");
                Object res = m2.invoke(info);
                if (res instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return "en_us";
    }

    private static BridgeSettings loadSettings() {
        String homeserver = MatrixBridgeConfig.HOMESERVER.get();
        String roomId = MatrixBridgeConfig.ROOM_ID.get();

        String tokenFromEnv = System.getenv("MATRIX_ACCESS_TOKEN");
        String accessToken = (tokenFromEnv != null && !tokenFromEnv.isBlank())
                ? tokenFromEnv
                : MatrixBridgeConfig.ACCESS_TOKEN.get();

        return new BridgeSettings(
                homeserver,
                roomId,
                accessToken,
                MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get(),
                MatrixBridgeConfig.ENABLE_MATRIX_TO_MC.get(),
                MatrixBridgeConfig.ANNOUNCE_CONNECTED.get(),
                MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get(),
                MatrixBridgeConfig.MATRIX_TO_MC_PREFIX.get(),
                MatrixBridgeConfig.MATRIX_BOT_PREFIX.get(),
                MatrixBridgeConfig.SYNC_TIMEOUT_MS.get(),
                MatrixBridgeConfig.TIMELINE_LIMIT.get(),
                MatrixBridgeConfig.MAX_QUEUE_SIZE.get(),
                MatrixBridgeConfig.DEDUP_SIZE.get(),
                MatrixBridgeConfig.ENABLE_EVENT_TAPS.get(),
                MatrixBridgeConfig.MAX_ACTIVE_EVENT_TAPS.get(),
                MatrixBridgeConfig.DEFAULT_EVENT_THROTTLE_MS.get(),
                MatrixBridgeConfig.EVENT_COMMAND_MIN_POWER_LEVEL.get()
        );
    }

    private int eventOn(CommandSourceStack source, String eventName, String filter, String duration) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            MC.sendFailure(source, MC.text("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"on", eventName, filter, duration};
        String result = mgr.handleCommand(args);
        MC.sendSuccess(source, MC.text(result), false);
        return 1;
    }

    private int eventOff(CommandSourceStack source, String eventName) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            MC.sendFailure(source, MC.text("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"off", eventName};
        String result = mgr.handleCommand(args);
        MC.sendSuccess(source, MC.text(result), false);
        return 1;
    }

    private int eventList(CommandSourceStack source) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            MC.sendFailure(source, MC.text("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"list"};
        String result = mgr.handleCommand(args);
        MC.sendSuccess(source, MC.text(result), false);
        return 1;
    }

    private int eventSearch(CommandSourceStack source, String query) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            MC.sendFailure(source, MC.text("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"search", query};
        String result = mgr.handleCommand(args);
        MC.sendSuccess(source, MC.text(result), false);
        return 1;
    }

    private int eventHelp(CommandSourceStack source) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            MC.sendFailure(source, MC.text("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"help"};
        String result = mgr.handleCommand(args);
        MC.sendSuccess(source, MC.text(result), false);
        return 1;
    }
}
