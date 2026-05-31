package space.controlnet.minecraftmatrixbridge;

import java.util.Locale;

/**
 * Utility methods for event tap filter handling.
 */
public final class FilterUtil {

    private FilterUtil() {
        // Utility class
    }

    /**
     * Normalizes a filter string for case-insensitive matching.
     *
     * <p>Returns {@code null} if the filter is null, empty, or blank (meaning "match all").
     * Otherwise, returns the lowercased, trimmed filter string.
     *
     * @param filter the raw filter string (may be null)
     * @return normalized filter, or null if no filtering should be applied
     */
    public static String normalize(String filter) {
        if (filter == null) {
            return null;
        }
        String trimmed = filter.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks if the given text matches the normalized filter.
     *
     * <p>If the filter is null (meaning "match all"), returns true.
     * Otherwise, performs a case-insensitive substring match.
     *
     * @param text             the text to check (e.g., serialized event)
     * @param normalizedFilter the normalized filter (result of {@link #normalize}), or null
     * @return true if the text matches, false otherwise
     */
    public static boolean matches(String text, String normalizedFilter) {
        if (normalizedFilter == null) {
            return true; // No filter = match all
        }
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(normalizedFilter);
    }

    /**
     * Checks if the given text matches the raw (non-normalized) filter.
     *
     * <p>Convenience method that normalizes the filter first.
     *
     * @param text   the text to check
     * @param filter the raw filter string (may be null)
     * @return true if the text matches, false otherwise
     */
    public static boolean matchesRaw(String text, String filter) {
        return matches(text, normalize(filter));
    }
}
