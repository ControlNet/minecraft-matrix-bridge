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

    public final int syncTimeoutMs;
    public final int timelineLimit;
    public final int maxQueueSize;
    public final int dedupSize;

    public BridgeSettings(
            String homeserver,
            String roomId,
            String accessToken,
            boolean enableMcToMatrix,
            boolean enableMatrixToMc,
            boolean announceConnected,
            String mcToMatrixPrefix,
            String matrixToMcPrefix,
            int syncTimeoutMs,
            int timelineLimit,
            int maxQueueSize,
            int dedupSize
    ) {
        this.homeserver = normalizeHomeserver(homeserver);
        this.roomId = trimToEmpty(roomId);
        this.accessToken = trimToEmpty(accessToken);

        this.enableMcToMatrix = enableMcToMatrix;
        this.enableMatrixToMc = enableMatrixToMc;
        this.announceConnected = announceConnected;
        this.mcToMatrixPrefix = defaultIfBlankPreserveWhitespace(mcToMatrixPrefix, "[MC] ");
        this.matrixToMcPrefix = defaultIfBlankPreserveWhitespace(matrixToMcPrefix, "[Matrix] ");

        this.syncTimeoutMs = syncTimeoutMs;
        this.timelineLimit = timelineLimit;
        this.maxQueueSize = maxQueueSize;
        this.dedupSize = dedupSize;
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
                ", syncTimeoutMs=" + syncTimeoutMs +
                ", timelineLimit=" + timelineLimit +
                ", maxQueueSize=" + maxQueueSize +
                ", dedupSize=" + dedupSize +
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
}
