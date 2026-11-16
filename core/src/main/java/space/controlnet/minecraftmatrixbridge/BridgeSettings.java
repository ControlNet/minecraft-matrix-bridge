package space.controlnet.minecraftmatrixbridge;

import java.util.Objects;

public final class BridgeSettings {
    public final String homeserver;
    public final String roomId;
    public final String accessToken;

    public final boolean enableMcToMatrix;
    public final boolean enableMatrixToMc;
    public final boolean announceConnected;
    public final String mcToMatrixPrefix;
    public final String matrixToMcPrefix;
    public final String matrixBotPrefix;

    public final int syncTimeoutMs;
    public final int timelineLimit;
    public final int maxQueueSize;
    public final int dedupSize;

    public final int maxActiveEventTaps;
    public final int defaultEventThrottleMs;
    public final int eventCommandMinPowerLevel;
    public final boolean enableEventTaps;

    public final boolean enableJoinLeaveToMatrix;
    public final boolean enableServerLifecycleToMatrix;

    public BridgeSettings(
            String homeserver,
            String roomId,
            String accessToken,
            boolean enableMcToMatrix,
            boolean enableMatrixToMc,
            boolean announceConnected,
            String mcToMatrixPrefix,
            String matrixToMcPrefix,
            String matrixBotPrefix,
            int syncTimeoutMs,
            int timelineLimit,
            int maxQueueSize,
            int dedupSize,
            boolean enableEventTaps,
            int maxActiveEventTaps,
            int defaultEventThrottleMs,
            int eventCommandMinPowerLevel,
            boolean enableJoinLeaveToMatrix,
            boolean enableServerLifecycleToMatrix
    ) {
        this.homeserver = normalizeHomeserver(homeserver);
        this.roomId = trimToEmpty(roomId);
        this.accessToken = trimToEmpty(accessToken);

        this.enableMcToMatrix = enableMcToMatrix;
        this.enableMatrixToMc = enableMatrixToMc;
        this.announceConnected = announceConnected;
        // An empty prefix is a valid choice: chat then reaches Matrix as "<player> text".
        this.mcToMatrixPrefix = mcToMatrixPrefix == null ? "[MC] " : mcToMatrixPrefix;
        this.matrixToMcPrefix = defaultIfBlankPreserveWhitespace(matrixToMcPrefix, "[Matrix] ");
        this.matrixBotPrefix = normalizeMatrixBotPrefix(matrixBotPrefix);

        this.syncTimeoutMs = syncTimeoutMs;
        this.timelineLimit = timelineLimit;
        this.maxQueueSize = maxQueueSize;
        this.dedupSize = dedupSize;

        this.enableEventTaps = enableEventTaps;
        this.maxActiveEventTaps = maxActiveEventTaps > 0 ? maxActiveEventTaps : 10;
        this.defaultEventThrottleMs = defaultEventThrottleMs > 0 ? defaultEventThrottleMs : 1000;
        // Room admins only by default: tap output can include player positions.
        this.eventCommandMinPowerLevel = eventCommandMinPowerLevel >= 0 ? eventCommandMinPowerLevel : 100;

        this.enableJoinLeaveToMatrix = enableJoinLeaveToMatrix;
        this.enableServerLifecycleToMatrix = enableServerLifecycleToMatrix;
    }

    public BridgeSettings(
            String homeserver,
            String roomId,
            String accessToken,
            boolean enableMcToMatrix,
            boolean enableMatrixToMc,
            boolean announceConnected,
            String mcToMatrixPrefix,
            String matrixToMcPrefix,
            String matrixBotPrefix,
            int syncTimeoutMs,
            int timelineLimit,
            int maxQueueSize,
            int dedupSize,
            boolean enableEventTaps,
            int maxActiveEventTaps,
            int defaultEventThrottleMs,
            int eventCommandMinPowerLevel
    ) {
        this(homeserver, roomId, accessToken, enableMcToMatrix, enableMatrixToMc, announceConnected,
                mcToMatrixPrefix, matrixToMcPrefix, matrixBotPrefix, syncTimeoutMs, timelineLimit,
                maxQueueSize, dedupSize, enableEventTaps, maxActiveEventTaps, defaultEventThrottleMs,
                eventCommandMinPowerLevel, false, false);
    }

    public BridgeSettings(
            String homeserver,
            String roomId,
            String accessToken,
            boolean enableMcToMatrix,
            boolean enableMatrixToMc,
            boolean announceConnected,
            String mcToMatrixPrefix,
            String matrixToMcPrefix,
            String matrixBotPrefix,
            int syncTimeoutMs,
            int timelineLimit,
            int maxQueueSize,
            int dedupSize
    ) {
        this(homeserver, roomId, accessToken, enableMcToMatrix, enableMatrixToMc, announceConnected,
                mcToMatrixPrefix, matrixToMcPrefix, matrixBotPrefix, syncTimeoutMs, timelineLimit,
                maxQueueSize, dedupSize, true, 10, 1000, 100);
    }

    public boolean isBridgeEnabled() {
        return enableMcToMatrix || enableMatrixToMc;
    }

    public boolean hasRequiredFields() {
        if (!isBridgeEnabled()) {
            return false;
        }
        return !homeserver.isBlank() && !roomId.isBlank() && !accessToken.isBlank();
    }

    @Override
    public String toString() {
        return "BridgeSettings{" +
                "homeserver='" + homeserver + '\'' +
                ", roomId='" + roomId + '\'' +
                ", accessToken='<redacted>'" +
                ", enableMcToMatrix=" + enableMcToMatrix +
                ", enableMatrixToMc=" + enableMatrixToMc +
                ", announceConnected=" + announceConnected +
                ", mcToMatrixPrefix='" + mcToMatrixPrefix + '\'' +
                ", matrixToMcPrefix='" + matrixToMcPrefix + '\'' +
                ", matrixBotPrefix='" + matrixBotPrefix + '\'' +
                ", syncTimeoutMs=" + syncTimeoutMs +
                ", timelineLimit=" + timelineLimit +
                ", maxQueueSize=" + maxQueueSize +
                ", dedupSize=" + dedupSize +
                ", enableEventTaps=" + enableEventTaps +
                ", maxActiveEventTaps=" + maxActiveEventTaps +
                ", defaultEventThrottleMs=" + defaultEventThrottleMs +
                ", eventCommandMinPowerLevel=" + eventCommandMinPowerLevel +
                ", enableJoinLeaveToMatrix=" + enableJoinLeaveToMatrix +
                ", enableServerLifecycleToMatrix=" + enableServerLifecycleToMatrix +
                '}';
    }

    private static String trimToEmpty(String s) {
        if (s == null) {
            return "";
        }
        return s.trim();
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        String v = trimToEmpty(value);
        return v.isBlank() ? Objects.requireNonNull(defaultValue) : v;
    }

    private static String defaultIfBlankPreserveWhitespace(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return Objects.requireNonNull(defaultValue);
        }
        return value;
    }

    private static String normalizeHomeserver(String homeserverRaw) {
        String s = trimToEmpty(homeserverRaw);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizeMatrixBotPrefix(String raw) {
        if (raw == null) {
            return "!mc";
        }
        String s = raw.trim();
        // Allow disabling by setting empty string in config.
        if (s.isEmpty()) {
            return "";
        }
        return s;
    }
}
