package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DurationSpecTest {

    // -------------------------------------------------------------------------
    // Factory method tests
    // -------------------------------------------------------------------------

    @Test
    void onceCreatesOnceMode() {
        DurationSpec spec = DurationSpec.once();
        assertEquals(DurationSpec.Mode.ONCE, spec.mode);
        assertEquals(0, spec.durationMs);
    }

    @Test
    void permanentCreatesPermanentMode() {
        DurationSpec spec = DurationSpec.permanent();
        assertEquals(DurationSpec.Mode.PERMANENT, spec.mode);
        assertEquals(0, spec.durationMs);
    }

    @Test
    void timedCreatesTimedMode() {
        DurationSpec spec = DurationSpec.timed(5000);
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(5000, spec.durationMs);
    }

    @Test
    void timedRejectsZeroDuration() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.timed(0));
    }

    @Test
    void timedRejectsNegativeDuration() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.timed(-1000));
    }

    // -------------------------------------------------------------------------
    // Parse tests - keywords
    // -------------------------------------------------------------------------

    @Test
    void parseOnce() {
        DurationSpec spec = DurationSpec.parse("once");
        assertEquals(DurationSpec.Mode.ONCE, spec.mode);
        assertEquals(0, spec.durationMs);
    }

    @Test
    void parseOnceUpperCase() {
        DurationSpec spec = DurationSpec.parse("ONCE");
        assertEquals(DurationSpec.Mode.ONCE, spec.mode);
    }

    @Test
    void parseOnceMixedCase() {
        DurationSpec spec = DurationSpec.parse("OnCe");
        assertEquals(DurationSpec.Mode.ONCE, spec.mode);
    }

    @Test
    void parsePermanent() {
        DurationSpec spec = DurationSpec.parse("permanent");
        assertEquals(DurationSpec.Mode.PERMANENT, spec.mode);
        assertEquals(0, spec.durationMs);
    }

    @Test
    void parsePermanentUpperCase() {
        DurationSpec spec = DurationSpec.parse("PERMANENT");
        assertEquals(DurationSpec.Mode.PERMANENT, spec.mode);
    }

    // -------------------------------------------------------------------------
    // Parse tests - timed durations
    // -------------------------------------------------------------------------

    @Test
    void parseSeconds() {
        DurationSpec spec = DurationSpec.parse("30s");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(30_000, spec.durationMs);
    }

    @Test
    void parseSecondsUpperCase() {
        DurationSpec spec = DurationSpec.parse("30S");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(30_000, spec.durationMs);
    }

    @Test
    void parseMinutes() {
        DurationSpec spec = DurationSpec.parse("10m");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(600_000, spec.durationMs);
    }

    @Test
    void parseMinutesUpperCase() {
        DurationSpec spec = DurationSpec.parse("10M");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(600_000, spec.durationMs);
    }

    @Test
    void parseHours() {
        DurationSpec spec = DurationSpec.parse("2h");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(7_200_000, spec.durationMs);
    }

    @Test
    void parseHoursUpperCase() {
        DurationSpec spec = DurationSpec.parse("2H");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(7_200_000, spec.durationMs);
    }

    @Test
    void parseDays() {
        DurationSpec spec = DurationSpec.parse("1d");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(86_400_000, spec.durationMs);
    }

    @Test
    void parseDaysUpperCase() {
        DurationSpec spec = DurationSpec.parse("1D");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(86_400_000, spec.durationMs);
    }

    @Test
    void parseMultipleDays() {
        DurationSpec spec = DurationSpec.parse("7d");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(7 * 86_400_000L, spec.durationMs);
    }

    @Test
    void parseWithWhitespace() {
        DurationSpec spec = DurationSpec.parse("  30s  ");
        assertEquals(DurationSpec.Mode.TIMED, spec.mode);
        assertEquals(30_000, spec.durationMs);
    }

    // -------------------------------------------------------------------------
    // Parse tests - invalid inputs
    // -------------------------------------------------------------------------

    @Test
    void parseNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse(null));
    }

    @Test
    void parseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse(""));
    }

    @Test
    void parseBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("   "));
    }

    @Test
    void parseInvalidFormatThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("abc"));
    }

    @Test
    void parseInvalidUnitThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("30x"));
    }

    @Test
    void parseZeroValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("0s"));
    }

    @Test
    void parseNegativeValueThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("-5s"));
    }

    @Test
    void parseNoNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse("s"));
    }

    // -------------------------------------------------------------------------
    // parseOrDefault tests
    // -------------------------------------------------------------------------

    @Test
    void parseOrDefaultReturnsDefaultOnNull() {
        DurationSpec def = DurationSpec.once();
        DurationSpec result = DurationSpec.parseOrDefault(null, def);
        assertSame(def, result);
    }

    @Test
    void parseOrDefaultReturnsDefaultOnEmpty() {
        DurationSpec def = DurationSpec.once();
        DurationSpec result = DurationSpec.parseOrDefault("", def);
        assertSame(def, result);
    }

    @Test
    void parseOrDefaultReturnsDefaultOnInvalid() {
        DurationSpec def = DurationSpec.once();
        DurationSpec result = DurationSpec.parseOrDefault("invalid", def);
        assertSame(def, result);
    }

    @Test
    void parseOrDefaultReturnsParsedOnValid() {
        DurationSpec def = DurationSpec.once();
        DurationSpec result = DurationSpec.parseOrDefault("30s", def);
        assertEquals(DurationSpec.Mode.TIMED, result.mode);
        assertEquals(30_000, result.durationMs);
    }

    // -------------------------------------------------------------------------
    // calculateExpiresAtMs tests
    // -------------------------------------------------------------------------

    @Test
    void calculateExpiresAtMsForOnce() {
        DurationSpec spec = DurationSpec.once();
        assertEquals(0, spec.calculateExpiresAtMs(1000));
    }

    @Test
    void calculateExpiresAtMsForPermanent() {
        DurationSpec spec = DurationSpec.permanent();
        assertEquals(0, spec.calculateExpiresAtMs(1000));
    }

    @Test
    void calculateExpiresAtMsForTimed() {
        DurationSpec spec = DurationSpec.timed(5000);
        assertEquals(6000, spec.calculateExpiresAtMs(1000));
    }

    // -------------------------------------------------------------------------
    // toDisplayString tests
    // -------------------------------------------------------------------------

    @Test
    void toDisplayStringOnce() {
        assertEquals("once", DurationSpec.once().toDisplayString());
    }

    @Test
    void toDisplayStringPermanent() {
        assertEquals("permanent", DurationSpec.permanent().toDisplayString());
    }

    @Test
    void toDisplayStringSeconds() {
        assertEquals("30s", DurationSpec.timed(30_000).toDisplayString());
    }

    @Test
    void toDisplayStringMinutes() {
        assertEquals("10m", DurationSpec.timed(600_000).toDisplayString());
    }

    @Test
    void toDisplayStringHours() {
        assertEquals("2h", DurationSpec.timed(7_200_000).toDisplayString());
    }

    @Test
    void toDisplayStringDays() {
        assertEquals("1d", DurationSpec.timed(86_400_000).toDisplayString());
    }

    @Test
    void toDisplayStringMixedSeconds() {
        // 90 seconds = 90000ms, not evenly divisible by minutes
        assertEquals("90s", DurationSpec.timed(90_000).toDisplayString());
    }

    // -------------------------------------------------------------------------
    // equals/hashCode tests
    // -------------------------------------------------------------------------

    @Test
    void equalsForSameSpec() {
        DurationSpec spec1 = DurationSpec.parse("30s");
        DurationSpec spec2 = DurationSpec.parse("30s");
        assertEquals(spec1, spec2);
        assertEquals(spec1.hashCode(), spec2.hashCode());
    }

    @Test
    void notEqualsForDifferentMode() {
        DurationSpec spec1 = DurationSpec.once();
        DurationSpec spec2 = DurationSpec.permanent();
        assertNotEquals(spec1, spec2);
    }

    @Test
    void notEqualsForDifferentDuration() {
        DurationSpec spec1 = DurationSpec.timed(30_000);
        DurationSpec spec2 = DurationSpec.timed(60_000);
        assertNotEquals(spec1, spec2);
    }
}
