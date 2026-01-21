package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FilterUtilTest {

    // -------------------------------------------------------------------------
    // normalize tests
    // -------------------------------------------------------------------------

    @Test
    void normalizeReturnsNullForNull() {
        assertNull(FilterUtil.normalize(null));
    }

    @Test
    void normalizeReturnsNullForEmpty() {
        assertNull(FilterUtil.normalize(""));
    }

    @Test
    void normalizeReturnsNullForBlank() {
        assertNull(FilterUtil.normalize("   "));
    }

    @Test
    void normalizeTrimsWhitespace() {
        assertEquals("hello", FilterUtil.normalize("  hello  "));
    }

    @Test
    void normalizeLowercases() {
        assertEquals("hello world", FilterUtil.normalize("Hello World"));
    }

    @Test
    void normalizeTrimsAndLowercases() {
        assertEquals("test filter", FilterUtil.normalize("  TEST Filter  "));
    }

    // -------------------------------------------------------------------------
    // matches tests with normalized filter
    // -------------------------------------------------------------------------

    @Test
    void matchesReturnsTrueForNullFilter() {
        // Null filter means "match all"
        assertTrue(FilterUtil.matches("any text", null));
    }

    @Test
    void matchesReturnsFalseForNullText() {
        assertFalse(FilterUtil.matches(null, "filter"));
    }

    @Test
    void matchesReturnsTrueForNullTextAndNullFilter() {
        // Null filter means "match all"
        assertTrue(FilterUtil.matches(null, null));
    }

    @Test
    void matchesFindsSubstring() {
        assertTrue(FilterUtil.matches("Hello World", "world"));
    }

    @Test
    void matchesCaseInsensitive() {
        assertTrue(FilterUtil.matches("HELLO WORLD", "world"));
    }

    @Test
    void matchesReturnsFalseWhenNotFound() {
        assertFalse(FilterUtil.matches("Hello World", "xyz"));
    }

    @Test
    void matchesFindsAtStart() {
        assertTrue(FilterUtil.matches("Hello World", "hello"));
    }

    @Test
    void matchesFindsAtEnd() {
        assertTrue(FilterUtil.matches("Hello World", "world"));
    }

    @Test
    void matchesFindsInMiddle() {
        assertTrue(FilterUtil.matches("Hello World", "lo wo"));
    }

    @Test
    void matchesExactMatch() {
        assertTrue(FilterUtil.matches("hello", "hello"));
    }

    // -------------------------------------------------------------------------
    // matchesRaw tests (convenience method)
    // -------------------------------------------------------------------------

    @Test
    void matchesRawNormalizesAndMatches() {
        assertTrue(FilterUtil.matchesRaw("Hello World", "  WORLD  "));
    }

    @Test
    void matchesRawReturnsTrueForNullFilter() {
        assertTrue(FilterUtil.matchesRaw("any text", null));
    }

    @Test
    void matchesRawReturnsTrueForEmptyFilter() {
        assertTrue(FilterUtil.matchesRaw("any text", ""));
    }

    @Test
    void matchesRawReturnsTrueForBlankFilter() {
        assertTrue(FilterUtil.matchesRaw("any text", "   "));
    }

    @Test
    void matchesRawReturnsFalseWhenNotFound() {
        assertFalse(FilterUtil.matchesRaw("Hello World", "xyz"));
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void normalizeHandlesSpecialChars() {
        assertEquals("[event]", FilterUtil.normalize("[EVENT]"));
    }

    @Test
    void matchesHandlesSpecialChars() {
        assertTrue(FilterUtil.matches("ServerChatEvent[player=Steve]", "[player="));
    }

    @Test
    void matchesEmptyTextWithFilter() {
        assertFalse(FilterUtil.matches("", "filter"));
    }

    @Test
    void matchesEmptyTextWithNullFilter() {
        assertTrue(FilterUtil.matches("", null));
    }
}
