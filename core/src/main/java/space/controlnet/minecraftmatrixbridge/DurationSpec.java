package space.controlnet.minecraftmatrixbridge;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an event tap duration specification.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code once} - fire once then auto-disable</li>
 *   <li>{@code permanent} - stay enabled until explicitly disabled</li>
 *   <li>{@code <N>s} - timed duration in seconds (e.g., {@code 30s})</li>
 *   <li>{@code <N>m} - timed duration in minutes (e.g., {@code 10m})</li>
 *   <li>{@code <N>h} - timed duration in hours (e.g., {@code 2h})</li>
 *   <li>{@code <N>d} - timed duration in days (e.g., {@code 1d})</li>
 * </ul>
 */
public final class DurationSpec {

    /**
     * Duration mode.
     */
    public enum Mode {
        /** Fire once, then auto-disable. */
        ONCE,
        /** Stay enabled for a fixed duration, then auto-disable. */
        TIMED,
        /** Stay enabled until explicitly disabled. */
        PERMANENT
    }

    private static final Pattern TIMED_PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    /** The duration mode. */
    public final Mode mode;

    /** Duration in milliseconds. Zero for {@link Mode#ONCE} and {@link Mode#PERMANENT}. */
    public final long durationMs;

    private DurationSpec(Mode mode, long durationMs) {
        this.mode = Objects.requireNonNull(mode);
        this.durationMs = durationMs;
    }

    /**
     * Creates a ONCE duration spec.
     */
    public static DurationSpec once() {
        return new DurationSpec(Mode.ONCE, 0);
    }

    /**
     * Creates a PERMANENT duration spec.
     */
    public static DurationSpec permanent() {
        return new DurationSpec(Mode.PERMANENT, 0);
    }

    /**
     * Creates a TIMED duration spec.
     *
     * @param durationMs duration in milliseconds (must be positive)
     * @throws IllegalArgumentException if durationMs is not positive
     */
    public static DurationSpec timed(long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        return new DurationSpec(Mode.TIMED, durationMs);
    }

    /**
     * Parses a duration specification string.
     *
     * @param input the input string (e.g., "once", "permanent", "30s", "10m", "2h", "1d")
     * @return the parsed DurationSpec
     * @throws IllegalArgumentException if the input cannot be parsed
     */
    public static DurationSpec parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration input cannot be null");
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Duration input cannot be empty");
        }

        if ("once".equals(s)) {
            return once();
        }
        if ("permanent".equals(s)) {
            return permanent();
        }

        Matcher m = TIMED_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration format: '" + input + "'. Expected: once, permanent, or <N>s|m|h|d");
        }

        long value;
        try {
            value = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration number: '" + m.group(1) + "'");
        }

        if (value <= 0) {
            throw new IllegalArgumentException("Duration value must be positive: " + value);
        }

        String unit = m.group(2).toLowerCase(Locale.ROOT);
        long multiplier = switch (unit) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };

        long durationMs = value * multiplier;
        // Overflow check
        if (durationMs < 0 || durationMs / multiplier != value) {
            throw new IllegalArgumentException("Duration overflow: " + input);
        }

        return timed(durationMs);
    }

    /**
     * Tries to parse a duration specification string, returning a default on failure.
     *
     * @param input        the input string
     * @param defaultValue the default value to return if parsing fails
     * @return the parsed DurationSpec, or defaultValue if parsing fails
     */
    public static DurationSpec parseOrDefault(String input, DurationSpec defaultValue) {
        if (input == null || input.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return parse(input);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * Calculates the expiration timestamp based on this spec.
     *
     * @param nowMs current time in milliseconds
     * @return expiration timestamp in milliseconds; 0 for ONCE/PERMANENT
     */
    public long calculateExpiresAtMs(long nowMs) {
        if (mode == Mode.TIMED) {
            return nowMs + durationMs;
        }
        return 0;
    }

    /**
     * Returns a human-readable description of this duration spec.
     */
    public String toDisplayString() {
        return switch (mode) {
            case ONCE -> "once";
            case PERMANENT -> "permanent";
            case TIMED -> formatDuration(durationMs);
        };
    }

    private static String formatDuration(long ms) {
        if (ms >= 86_400_000L && ms % 86_400_000L == 0) {
            return (ms / 86_400_000L) + "d";
        }
        if (ms >= 3_600_000L && ms % 3_600_000L == 0) {
            return (ms / 3_600_000L) + "h";
        }
        if (ms >= 60_000L && ms % 60_000L == 0) {
            return (ms / 60_000L) + "m";
        }
        return (ms / 1_000L) + "s";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DurationSpec that = (DurationSpec) o;
        return durationMs == that.durationMs && mode == that.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, durationMs);
    }

    @Override
    public String toString() {
        return "DurationSpec{mode=" + mode + ", durationMs=" + durationMs + "}";
    }
}
