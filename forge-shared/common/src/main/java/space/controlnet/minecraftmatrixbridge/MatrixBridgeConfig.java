package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.common.ForgeConfigSpec;

public final class MatrixBridgeConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> HOMESERVER;
    public static final ForgeConfigSpec.ConfigValue<String> ROOM_ID;
    public static final ForgeConfigSpec.ConfigValue<String> ACCESS_TOKEN;

    public static final ForgeConfigSpec.BooleanValue ENABLE_MC_TO_MATRIX;
    public static final ForgeConfigSpec.BooleanValue ENABLE_MATRIX_TO_MC;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_CONNECTED;
    public static final ForgeConfigSpec.BooleanValue ENABLE_JOIN_LEAVE_TO_MATRIX;
    public static final ForgeConfigSpec.BooleanValue ENABLE_SERVER_LIFECYCLE_TO_MATRIX;
    public static final ForgeConfigSpec.ConfigValue<String> MC_TO_MATRIX_PREFIX;
    public static final ForgeConfigSpec.ConfigValue<String> MATRIX_TO_MC_PREFIX;
    public static final ForgeConfigSpec.ConfigValue<String> MATRIX_BOT_PREFIX;
    public static final ForgeConfigSpec.IntValue SYNC_TIMEOUT_MS;
    public static final ForgeConfigSpec.IntValue TIMELINE_LIMIT;
    public static final ForgeConfigSpec.IntValue MAX_QUEUE_SIZE;
    public static final ForgeConfigSpec.IntValue DEDUP_SIZE;

    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_EVENT_TAPS;
    public static final ForgeConfigSpec.IntValue DEFAULT_EVENT_THROTTLE_MS;
    public static final ForgeConfigSpec.IntValue EVENT_COMMAND_MIN_POWER_LEVEL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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

        MAX_ACTIVE_EVENT_TAPS = builder.comment("Maximum number of active event tap subscriptions.")
                .defineInRange("maxActiveEventTaps", 10, 1, 100);
        DEFAULT_EVENT_THROTTLE_MS = builder.comment("Default throttle interval in ms between forwarded events.")
                .defineInRange("defaultEventThrottleMs", 1000, 100, 60_000);
        EVENT_COMMAND_MIN_POWER_LEVEL = builder.comment("Minimum Matrix power level required to use event commands from Matrix.",
                        "Default 50 (moderator). Set to 0 to allow all room members.")
                .defineInRange("eventCommandMinPowerLevel", 50, 0, 100);
        builder.pop();

        SPEC = builder.build();
    }

    private MatrixBridgeConfig() {
    }
}
