package space.controlnet.minecraftmatrixbridge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

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

public final class NeoForgeHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CONNECTED_NOTICE_DELAY_TICKS = 20;

    private BridgeService bridgeService;
    private McCallbacks callbacks;
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
                        names.add(p.getName().getString());
                    }
                    Collections.sort(names);
                    fut.complete(names);
                });
                return fut;
            }
        };
        bridgeService.start(loadSettings(), worldRoot, callbacks);

        if (MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get() && MatrixBridgeConfig.ENABLE_SERVER_LIFECYCLE_TO_MATRIX.get()) {
            String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* Server started.";
            bridgeService.enqueueMcMessage(formatted);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BridgeService service = bridgeService;
        bridgeService = null;
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

        String playerName = event.getPlayer().getName().getString();
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "<" + playerName + "> " + msg;
        bridgeService.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        BridgeService service = bridgeService;

        if (service != null && service.isRunning()
                && MatrixBridgeConfig.ENABLE_MC_TO_MATRIX.get()
                && MatrixBridgeConfig.ENABLE_JOIN_LEAVE_TO_MATRIX.get()) {
            String playerName = event.getEntity().getName().getString();
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
    public void onServerTick(ServerTickEvent.Post event) {
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
            int left = (e.getValue() == null ? 0 : e.getValue()) - 1;
            if (left > 0) {
                pendingConnectedNoticeTicks.put(id, left);
                continue;
            }
            pendingConnectedNoticeTicks.remove(id);
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p == null) {
                continue;
            }
            String lang = getPlayerLanguage(p);
            String msg = Localizer.connected(lang, room);
            p.sendSystemMessage(Component.literal(msg));
        }
    }

    private void scheduleConnectedNotice(Object player) {
        if (!(player instanceof ServerPlayer p)) {
            return;
        }
        pendingConnectedNoticeTicks.put(p.getUUID(), CONNECTED_NOTICE_DELAY_TICKS);
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

        String playerName = event.getEntity().getName().getString();
        String formatted = MatrixBridgeConfig.MC_TO_MATRIX_PREFIX.get() + "* " + playerName + " left the game";
        service.enqueueMcMessage(formatted);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("matrix")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> {
                    if (bridgeService == null) {
                        ctx.getSource().sendSuccess(() -> Component.literal("MatrixBridge: not initialized."), false);
                        return 0;
                    }
                    String msg = "MatrixBridge: running=" + bridgeService.isRunning()
                            + ", ready=" + bridgeService.isReady()
                            + ", selfUserId=" + (bridgeService.getSelfUserId().isBlank() ? "<unknown>" : bridgeService.getSelfUserId())
                            + ", queue=" + bridgeService.getQueueSize();
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
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
                                    names.add(p.getName().getString());
                                }
                                Collections.sort(names);
                                fut.complete(names);
                            });
                            return fut;
                        }
                    };
                    bridgeService.start(loadSettings(), worldRoot, callbacks);
                    ctx.getSource().sendSuccess(() -> Component.literal("MatrixBridge reload requested."), true);
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
                        ctx.getSource().sendSuccess(() -> Component.literal("Queued test message."), false);
                        return 1;
                    }
                    ctx.getSource().sendFailure(Component.literal("Failed to queue test message (queue full or bridge not ready)."));
                    return 0;
                }));

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
                MatrixBridgeConfig.DEDUP_SIZE.get()
        );
    }
}
