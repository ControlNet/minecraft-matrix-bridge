package space.controlnet.minecraftmatrixbridge;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MatrixBridgeConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> HOMESERVER;
    public static final ModConfigSpec.ConfigValue<String> ROOM_ID;
    public static final ModConfigSpec.ConfigValue<String> ACCESS_TOKEN;

    public static final ModConfigSpec.BooleanValue ENABLE_MC_TO_MATRIX;
    public static final ModConfigSpec.BooleanValue ENABLE_MATRIX_TO_MC;
    public static final ModConfigSpec.BooleanValue ANNOUNCE_CONNECTED;
    public static final ModConfigSpec.BooleanValue ENABLE_JOIN_LEAVE_TO_MATRIX;
    public static final ModConfigSpec.BooleanValue ENABLE_SERVER_LIFECYCLE_TO_MATRIX;
    public static final ModConfigSpec.ConfigValue<String> MC_TO_MATRIX_PREFIX;
    public static final ModConfigSpec.ConfigValue<String> MATRIX_TO_MC_PREFIX;
    public static final ModConfigSpec.ConfigValue<String> MATRIX_BOT_PREFIX;
    public static final ModConfigSpec.IntValue SYNC_TIMEOUT_MS;
    public static final ModConfigSpec.IntValue TIMELINE_LIMIT;
    public static final ModConfigSpec.IntValue MAX_QUEUE_SIZE;
    public static final ModConfigSpec.IntValue DEDUP_SIZE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Matrix connection settings").push("matrix");
        HOMESERVER = builder.define("homeserver", "");
        ROOM_ID = builder.comment("Room ID (!...) or room alias (#...); aliases are resolved to a room_id at startup.")
                .define("roomId", "");
        ACCESS_TOKEN = builder.comment("Access token; overridden by env var MATRIX_ACCESS_TOKEN when set.")
                .define("accessToken", "");
        builder.pop();

        builder.comment("Bridge settings").push("bridge");
        ENABLE_MC_TO_MATRIX = builder.define("enableMcToMatrix", true);
        ENABLE_MATRIX_TO_MC = builder.define("enableMatrixToMc", true);
        ANNOUNCE_CONNECTED = builder.comment("Broadcast a chat notice when the bridge becomes connected, and notify players on login while connected.")
                .define("announceConnected", true);
        ENABLE_JOIN_LEAVE_TO_MATRIX = builder.comment("Send player join/leave notices from Minecraft to Matrix (MC -> Matrix).")
                .define("enableJoinLeaveToMatrix", false);
        ENABLE_SERVER_LIFECYCLE_TO_MATRIX = builder.comment("Send server lifecycle notices (started/stopping) from Minecraft to Matrix (MC -> Matrix).")
                .define("enableServerLifecycleToMatrix", false);
        MC_TO_MATRIX_PREFIX = builder.define("mcToMatrixPrefix", "[MC] ");
        MATRIX_TO_MC_PREFIX = builder.define("matrixToMcPrefix", "[Matrix] ");
        MATRIX_BOT_PREFIX = builder.comment("Matrix room bot command prefix. Messages starting with this prefix are treated as bot commands and are not forwarded to Minecraft chat.")
                .define("matrixBotPrefix", "!mc");
        SYNC_TIMEOUT_MS = builder.defineInRange("syncTimeoutMs", 30_000, 1_000, 600_000);
        TIMELINE_LIMIT = builder.defineInRange("timelineLimit", 20, 1, 1_000);
        MAX_QUEUE_SIZE = builder.defineInRange("maxQueueSize", 1_000, 1, 100_000);
        DEDUP_SIZE = builder.defineInRange("dedupSize", 512, 1, 100_000);
        builder.pop();

        SPEC = builder.build();
    }

    private MatrixBridgeConfig() {
    }
}

