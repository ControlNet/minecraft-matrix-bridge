package space.controlnet.minecraftmatrixbridge;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Utility methods for serializing events to bounded, human-readable strings.
 *
 * <p>This utility is designed to be safe and conservative:
 * <ul>
 *   <li>Uses the event's own {@link Object#toString()} when it has one (records, most
 *       26.x events). Otherwise (most Forge/NeoForge events) it lists the public
 *       zero-argument getters, skipping event-bus plumbing, so the output and filters
 *       see the player, position, message and so on instead of {@code Class@hash}</li>
 *   <li>Getter failures are skipped and each value is capped at
 *       {@value #MAX_GETTER_VALUE_LENGTH} characters</li>
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

    static final int MAX_GETTER_VALUE_LENGTH = 200;
    private static final int MAX_GETTERS = 32;
    /** Event-bus plumbing that says nothing about what happened. */
    private static final Set<String> EXCLUDED_GETTERS = Set.of(
            "getListenerList", "isCancelable", "hasResult", "isCanceled", "getResult", "getPhase");

    private record Getter(String property, Method method) {
    }

    private static final ClassValue<List<Getter>> GETTERS = new ClassValue<>() {
        @Override
        protected List<Getter> computeValue(Class<?> type) {
            return findGetters(type);
        }
    };

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
            raw = event == null ? "null" : describe(event);
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

    private static String describe(Object event) {
        if (!overridesToString(event.getClass())) {
            String described = describeByGetters(event);
            if (!described.isEmpty()) {
                return described;
            }
        }
        return event.toString();
    }

    private static boolean overridesToString(Class<?> type) {
        try {
            return type.getMethod("toString").getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException | SecurityException e) {
            return true;
        }
    }

    private static String describeByGetters(Object event) {
        StringBuilder out = new StringBuilder();
        for (Getter getter : GETTERS.get(event.getClass())) {
            String value;
            try {
                value = valueToString(getter.method().invoke(event));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                continue; // A getter that throws (or is inaccessible) is simply omitted.
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(getter.property()).append('=')
                    .append(truncate(normalizeWhitespace(value), MAX_GETTER_VALUE_LENGTH));
        }
        return out.toString();
    }

    private static String valueToString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[] array) {
            return Arrays.deepToString(array);
        }
        if (value.getClass().isArray()) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(String.valueOf(Array.get(value, i)));
            }
            return "[" + String.join(", ", items) + "]";
        }
        return String.valueOf(value);
    }

    private static List<Getter> findGetters(Class<?> type) {
        List<Getter> getters = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class
                    || Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()
                    || method.getDeclaringClass() == Object.class || EXCLUDED_GETTERS.contains(method.getName())) {
                continue;
            }
            String property = propertyName(method.getName());
            if (property != null && seen.add(method.getName())) {
                getters.add(new Getter(property, method));
            }
        }
        getters.sort(Comparator.comparing(Getter::property));
        return List.copyOf(getters.size() > MAX_GETTERS ? getters.subList(0, MAX_GETTERS) : getters);
    }

    private static String propertyName(String methodName) {
        for (String prefix : new String[]{"get", "is", "has"}) {
            if (methodName.length() > prefix.length() && methodName.startsWith(prefix)
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                if ("has".equals(prefix)) {
                    return methodName;
                }
                String rest = methodName.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
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

    /** Like {@link #formatForMatrix(Object, int)} but reuses an already serialized payload. */
    static String formatForMatrix(Object event, String serialized, int maxLength) {
        if (event == null) {
            return truncate("[EVT] null", maxLength);
        }
        String prefix = prefixFor(event);
        int remainingLen = maxLength - prefix.length();
        if (remainingLen < 10) {
            return truncate(prefix, maxLength);
        }
        return prefix + truncate(serialized == null ? "null" : serialized, remainingLen);
    }

    private static String prefixFor(Object event) {
        String simpleName = event.getClass().getSimpleName();
        if (simpleName.isEmpty()) {
            simpleName = event.getClass().getName();
        }
        return "[EVT] " + simpleName + ": ";
    }
}
