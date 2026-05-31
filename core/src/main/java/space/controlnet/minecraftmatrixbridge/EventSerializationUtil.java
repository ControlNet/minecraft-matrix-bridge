package space.controlnet.minecraftmatrixbridge;

/**
 * Utility methods for serializing events to bounded, human-readable strings.
 *
 * <p>This utility is designed to be safe and conservative:
 * <ul>
 *   <li>Uses only {@link Object#toString()} - no reflection</li>
 *   <li>Truncates output to a configurable maximum length</li>
 *   <li>Normalizes newlines and excessive whitespace</li>
 *   <li>Handles null inputs gracefully</li>
 * </ul>
 */
public final class EventSerializationUtil {

    /** Default maximum output length in characters. */
    public static final int DEFAULT_MAX_LENGTH = 2048;

    /** Truncation suffix appended when output is truncated. */
    private static final String TRUNCATION_SUFFIX = "...";

    private EventSerializationUtil() {
        // Utility class
    }

    /**
     * Serializes an event to a bounded, single-line string.
     *
     * @param event the event object (may be null)
     * @return serialized string, never longer than {@link #DEFAULT_MAX_LENGTH}
     */
    public static String serialize(Object event) {
        return serialize(event, DEFAULT_MAX_LENGTH);
    }

    /**
     * Serializes an event to a bounded, single-line string.
     *
     * @param event     the event object (may be null)
     * @param maxLength maximum output length (must be positive; at least 10 recommended)
     * @return serialized string, never longer than maxLength
     */
    public static String serialize(Object event, int maxLength) {
        if (maxLength < 1) {
            maxLength = DEFAULT_MAX_LENGTH;
        }

        String raw;
        try {
            raw = event == null ? "null" : event.toString();
        } catch (Exception e) {
            // Defensive: toString() might throw
            raw = event == null ? "null" : event.getClass().getName() + "@<toString-error>";
        }

        if (raw == null) {
            raw = "null";
        }

        // Normalize: replace newlines and carriage returns with spaces
        String normalized = normalizeWhitespace(raw);

        // Truncate if necessary
        return truncate(normalized, maxLength);
    }

    /**
     * Normalizes whitespace in the input string.
     *
     * <p>Replaces newlines, carriage returns, tabs, and runs of multiple spaces with single spaces.
     * Trims leading/trailing whitespace.
     *
     * @param input the input string
     * @return normalized string
     */
    public static String normalizeWhitespace(String input) {
        if (input == null) {
            return "";
        }
        // Replace CR, LF, and tabs with spaces
        String result = input.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        // Collapse multiple spaces into one
        result = result.replaceAll(" {2,}", " ");
        return result.trim();
    }

    /**
     * Truncates a string to the specified maximum length.
     *
     * <p>If truncation is needed, appends "..." to indicate the string was cut off.
     * The total length including the suffix will not exceed maxLength.
     *
     * @param input     the input string
     * @param maxLength the maximum length
     * @return truncated string
     */
    public static String truncate(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        if (maxLength < 1) {
            return "";
        }
        if (input.length() <= maxLength) {
            return input;
        }

        // Reserve space for truncation suffix
        int suffixLen = TRUNCATION_SUFFIX.length();
        if (maxLength <= suffixLen) {
            // Very short maxLength - just return what we can
            return input.substring(0, maxLength);
        }

        int cutoff = maxLength - suffixLen;
        return input.substring(0, cutoff) + TRUNCATION_SUFFIX;
    }

    /**
     * Formats an event for Matrix output with a prefix.
     *
     * @param event     the event object
     * @param maxLength maximum total length of the output
     * @return formatted string like "[EVT] SimpleClassName: serialized..."
     */
    public static String formatForMatrix(Object event, int maxLength) {
        if (event == null) {
            return truncate("[EVT] null", maxLength);
        }

        String simpleName = event.getClass().getSimpleName();
        if (simpleName.isEmpty()) {
            simpleName = event.getClass().getName();
        }

        String prefix = "[EVT] " + simpleName + ": ";
        int remainingLen = maxLength - prefix.length();
        if (remainingLen < 10) {
            // Not enough space for meaningful content
            return truncate(prefix, maxLength);
        }

        String serialized = serialize(event, remainingLen);
        return prefix + serialized;
    }
}
