package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EventSerializationUtilTest {

    // -------------------------------------------------------------------------
    // serialize tests - basic
    // -------------------------------------------------------------------------

    @Test
    void serializeNull() {
        assertEquals("null", EventSerializationUtil.serialize(null));
    }

    @Test
    void serializeSimpleObject() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "TestObject";
            }
        };
        assertEquals("TestObject", EventSerializationUtil.serialize(obj));
    }

    @Test
    void serializeString() {
        assertEquals("hello", EventSerializationUtil.serialize("hello"));
    }

    @Test
    void serializeInteger() {
        assertEquals("42", EventSerializationUtil.serialize(42));
    }

    // -------------------------------------------------------------------------
    // serialize tests - whitespace normalization
    // -------------------------------------------------------------------------

    @Test
    void serializeNormalizesNewlines() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "line1\nline2\nline3";
            }
        };
        assertEquals("line1 line2 line3", EventSerializationUtil.serialize(obj));
    }

    @Test
    void serializeNormalizesCarriageReturns() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "line1\r\nline2\r\nline3";
            }
        };
        assertEquals("line1 line2 line3", EventSerializationUtil.serialize(obj));
    }

    @Test
    void serializeNormalizesTabs() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "col1\tcol2\tcol3";
            }
        };
        assertEquals("col1 col2 col3", EventSerializationUtil.serialize(obj));
    }

    @Test
    void serializeCollapsesMultipleSpaces() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "word1    word2     word3";
            }
        };
        assertEquals("word1 word2 word3", EventSerializationUtil.serialize(obj));
    }

    @Test
    void serializeTrimsWhitespace() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "  trimmed  ";
            }
        };
        assertEquals("trimmed", EventSerializationUtil.serialize(obj));
    }

    // -------------------------------------------------------------------------
    // serialize tests - truncation
    // -------------------------------------------------------------------------

    @Test
    void serializeTruncatesLongOutput() {
        String longString = "x".repeat(3000);
        Object obj = new Object() {
            @Override
            public String toString() {
                return longString;
            }
        };
        String result = EventSerializationUtil.serialize(obj);
        assertTrue(result.length() <= EventSerializationUtil.DEFAULT_MAX_LENGTH);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void serializeWithCustomMaxLength() {
        String str = "This is a longer test string that should be truncated";
        String result = EventSerializationUtil.serialize(str, 20);
        assertEquals(20, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void serializeDoesNotTruncateShortStrings() {
        String str = "short";
        String result = EventSerializationUtil.serialize(str, 100);
        assertEquals("short", result);
    }

    @Test
    void serializeHandlesZeroMaxLength() {
        // Should fall back to default
        String longString = "x".repeat(3000);
        String result = EventSerializationUtil.serialize(longString, 0);
        assertTrue(result.length() <= EventSerializationUtil.DEFAULT_MAX_LENGTH);
    }

    @Test
    void serializeHandlesNegativeMaxLength() {
        // Should fall back to default
        String longString = "x".repeat(3000);
        String result = EventSerializationUtil.serialize(longString, -1);
        assertTrue(result.length() <= EventSerializationUtil.DEFAULT_MAX_LENGTH);
    }

    // -------------------------------------------------------------------------
    // serialize tests - error handling
    // -------------------------------------------------------------------------

    @Test
    void serializeHandlesToStringThrowingException() {
        Object obj = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("toString failed");
            }
        };
        String result = EventSerializationUtil.serialize(obj);
        assertTrue(result.contains("<toString-error>"));
    }

    @Test
    void serializeHandlesToStringReturningNull() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return null;
            }
        };
        assertEquals("null", EventSerializationUtil.serialize(obj));
    }

    // -------------------------------------------------------------------------
    // normalizeWhitespace tests
    // -------------------------------------------------------------------------

    @Test
    void normalizeWhitespaceNull() {
        assertEquals("", EventSerializationUtil.normalizeWhitespace(null));
    }

    @Test
    void normalizeWhitespaceEmpty() {
        assertEquals("", EventSerializationUtil.normalizeWhitespace(""));
    }

    @Test
    void normalizeWhitespaceOnlySpaces() {
        assertEquals("", EventSerializationUtil.normalizeWhitespace("    "));
    }

    @Test
    void normalizeWhitespaceMixed() {
        assertEquals("a b c d", EventSerializationUtil.normalizeWhitespace("  a\tb\nc\r\nd  "));
    }

    // -------------------------------------------------------------------------
    // truncate tests
    // -------------------------------------------------------------------------

    @Test
    void truncateNull() {
        assertEquals("", EventSerializationUtil.truncate(null, 100));
    }

    @Test
    void truncateShortString() {
        assertEquals("hello", EventSerializationUtil.truncate("hello", 100));
    }

    @Test
    void truncateExactLength() {
        assertEquals("hello", EventSerializationUtil.truncate("hello", 5));
    }

    @Test
    void truncateLongString() {
        String result = EventSerializationUtil.truncate("hello world", 8);
        assertEquals("hello...", result);
        assertEquals(8, result.length());
    }

    @Test
    void truncateVeryShortMaxLength() {
        // MaxLength <= suffix length: just return what we can
        String result = EventSerializationUtil.truncate("hello world", 3);
        assertEquals("hel", result);
    }

    @Test
    void truncateZeroMaxLength() {
        assertEquals("", EventSerializationUtil.truncate("hello", 0));
    }

    // -------------------------------------------------------------------------
    // formatForMatrix tests
    // -------------------------------------------------------------------------

    @Test
    void formatForMatrixNull() {
        String result = EventSerializationUtil.formatForMatrix(null, 100);
        assertEquals("[EVT] null", result);
    }

    @Test
    void formatForMatrixSimpleObject() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "event data";
            }
        };
        String result = EventSerializationUtil.formatForMatrix(obj, 100);
        assertTrue(result.startsWith("[EVT] "));
        assertTrue(result.contains(": event data"));
    }

    @Test
    void formatForMatrixTruncatesLongContent() {
        String longContent = "x".repeat(1000);
        Object obj = new Object() {
            @Override
            public String toString() {
                return longContent;
            }
        };
        String result = EventSerializationUtil.formatForMatrix(obj, 100);
        assertTrue(result.length() <= 100);
        assertTrue(result.startsWith("[EVT] "));
        assertTrue(result.endsWith("..."));
    }

    @Test
    void formatForMatrixVeryShortMaxLength() {
        Object obj = new Object() {
            @Override
            public String toString() {
                return "event data";
            }
        };
        String result = EventSerializationUtil.formatForMatrix(obj, 10);
        assertTrue(result.length() <= 10);
    }

    @Test
    void formatForMatrixIncludesSimpleClassName() {
        String result = EventSerializationUtil.formatForMatrix("test", 100);
        assertTrue(result.contains("String"));
    }
}
