package space.controlnet.minecraftmatrixbridge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ForgeHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_WAIT_TICKS = 100; // 5 seconds max wait for client settings
    // Default ClientInformation values (from ClientInformation.createDefault())
    private static final int DEFAULT_VIEW_DISTANCE = 2;
    private static final int DEFAULT_MODEL_CUSTOMISATION = 0;

    private BridgeService bridgeService;
    private McCallbacks callbacks;
    private EventTapManager eventTapManager;
    private volatile MinecraftServer runningServer;
    private volatile String connectedRoomIdOrAlias = "";
    private final ConcurrentHashMap<UUID, Integer> pendingConnectedNoticeTicks = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        this.runningServer = server;
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

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
                s.execute(() -> s.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
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
	                            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
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
                                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                                    names.add(p.getGameProfile().getName());
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
                    MinecraftForge.EVENT_BUS,
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
        eventTapManager = null;
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

        String playerName = event.getPlayer().getGameProfile().getName();
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "<" + playerName + "> " + msg;
        bridgeService.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        BridgeService service = bridgeService;

        if (service != null && service.isRunning()
                && MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()
                && MatrixBridgeConfig.ENABLE_JOIN_LEAVE_TO_MATRIX.get()) {
            String playerName = event.getEntity().getGameProfile().getName();
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

            ServerPlayer p = s.getPlayerList().getPlayer(id);
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
                p.sendSystemMessage(Component.literal(msg));
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
        // Try different method names across MC versions
        try {
            Method m = player.getClass().getMethod("requestedViewDistance");
            Object res = m.invoke(player);
            if (res instanceof Integer i) {
                return i;
            }
        } catch (Exception ignored) {
        }
        try {
            // Older versions might use clientViewDistance or similar
            Method m = player.getClass().getMethod("getRequestedViewDistance");
            Object res = m.invoke(player);
            if (res instanceof Integer i) {
                return i;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_VIEW_DISTANCE;
    }

    private static int getPlayerModelCustomisation(ServerPlayer player) {
        try {
            // Get the entity data for model customisation
            // This is stored in the synched entity data
            Method getEntityData = player.getClass().getMethod("getEntityData");
            Object entityData = getEntityData.invoke(player);
            if (entityData != null) {
                // The model customisation is typically a byte value
                // We need to find the DATA_PLAYER_MODE_CUSTOMISATION accessor
                // This is complex due to obfuscation, so we'll use a simpler heuristic
            }
        } catch (Exception ignored) {
        }
        // Fall back to checking if language is non-default as additional heuristic
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
        pendingConnectedNoticeTicks.put(p.getUUID(), 0);
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

        String playerName = event.getEntity().getGameProfile().getName();
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* " + playerName + " left the game";
        service.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("matrix")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> {
                    if (bridgeService == null) {
                        ForgeCommandCompat.sendSuccess(ctx.getSource(), Component.literal("MatrixBridge: not initialized."), false);
                        return 0;
                    }
                    String msg = "MatrixBridge: running=" + bridgeService.isRunning()
                            + ", ready=" + bridgeService.isReady()
                            + ", selfUserId=" + (bridgeService.getSelfUserId().isBlank() ? "<unknown>" : bridgeService.getSelfUserId())
                            + ", queue=" + bridgeService.getQueueSize();
                    ForgeCommandCompat.sendSuccess(ctx.getSource(), Component.literal(msg), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    Path worldRoot = server.getWorldPath(LevelResource.ROOT);

                    if (bridgeService != null) {
                        bridgeService.stop();
                    }
                    eventTapManager = null; // Clear old event tap manager
                    bridgeService = new BridgeService();
                    callbacks = new McCallbacks() {
                        @Override
                        public void broadcast(String text) {
                            MinecraftServer s = server;
                            if (s == null) {
                                return;
                            }
                            s.execute(() -> s.getPlayerList().broadcastSystemMessage(Component.literal(text), false));
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
                                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
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
                                for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                                    names.add(p.getGameProfile().getName());
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
                                MinecraftForge.EVENT_BUS,
                                settings.maxActiveEventTaps,
                                settings.defaultEventThrottleMs
                        );
                    }

                    ForgeCommandCompat.sendSuccess(ctx.getSource(), Component.literal("MatrixBridge reload requested."), true);
                    return 1;
                }))
                .then(Commands.literal("test").executes(ctx -> {
                    if (bridgeService == null || !bridgeService.isRunning()) {
                        ctx.getSource().sendFailure(Component.literal("MatrixBridge is not running."));
                        return 0;
                    }
                    if (!MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()) {
                        ctx.getSource().sendFailure(Component.literal("MC → Matrix is disabled (enableMcToMatrix=false)."));
                        return 0;
                    }
                    String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "[TEST] " + Instant.now();
                    boolean queued = bridgeService.enqueueMcMessage(formatted);
                    if (queued) {
                        ForgeCommandCompat.sendSuccess(ctx.getSource(), Component.literal("Queued test message."), false);
                        return 1;
                    }
                    ctx.getSource().sendFailure(Component.literal("Failed to queue test message (queue full or bridge not ready)."));
                    return 0;
                }))
                .then(Commands.literal("event")
                    .then(Commands.literal("on")
                        .then(Commands.argument("eventName", StringArgumentType.string())
                            .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), "", "once"))
                            .then(Commands.argument("filter", StringArgumentType.string())
                                .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), StringArgumentType.getString(ctx, "filter"), "once"))
                                .then(Commands.argument("duration", StringArgumentType.string())
                                    .executes(ctx -> eventOn(ctx.getSource(), StringArgumentType.getString(ctx, "eventName"), StringArgumentType.getString(ctx, "filter"), StringArgumentType.getString(ctx, "duration")))))))
                    .then(Commands.literal("off")
                        .then(Commands.argument("eventName", StringArgumentType.string())
                            .executes(ctx -> eventOff(ctx.getSource(), StringArgumentType.getString(ctx, "eventName")))))
                    .then(Commands.literal("list")
                        .executes(ctx -> eventList(ctx.getSource())))
                    .then(Commands.literal("search")
                        .then(Commands.argument("query", StringArgumentType.string())
                            .executes(ctx -> eventSearch(ctx.getSource(), StringArgumentType.getString(ctx, "query")))))
                    .then(Commands.literal("help")
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
            source.sendFailure(Component.literal("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"on", eventName, filter, duration};
        String result = mgr.handleCommand(args);
        ForgeCommandCompat.sendSuccess(source, Component.literal(result), false);
        return 1;
    }

    private int eventOff(CommandSourceStack source, String eventName) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            source.sendFailure(Component.literal("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"off", eventName};
        String result = mgr.handleCommand(args);
        ForgeCommandCompat.sendSuccess(source, Component.literal(result), false);
        return 1;
    }

    private int eventList(CommandSourceStack source) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            source.sendFailure(Component.literal("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"list"};
        String result = mgr.handleCommand(args);
        ForgeCommandCompat.sendSuccess(source, Component.literal(result), false);
        return 1;
    }

    private int eventSearch(CommandSourceStack source, String query) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            source.sendFailure(Component.literal("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"search", query};
        String result = mgr.handleCommand(args);
        ForgeCommandCompat.sendSuccess(source, Component.literal(result), false);
        return 1;
    }

    private int eventHelp(CommandSourceStack source) {
        EventTapManager mgr = eventTapManager;
        if (mgr == null) {
            source.sendFailure(Component.literal("Event taps are disabled in configuration."));
            return 0;
        }
        String[] args = {"help"};
        String result = mgr.handleCommand(args);
        ForgeCommandCompat.sendSuccess(source, Component.literal(result), false);
        return 1;
    }
}
