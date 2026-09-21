package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic permission and profile API shapes; no Minecraft authentication is simulated. */
class Forge121ApiTest {
    @Test
    void ownedWatcherWorkaroundIsLimitedToForge59OnMinecraft1219() {
        assertTrue(Forge121Api.needsOwnedWatcherCleanup("1.21.9", "59.0.5"));
        assertFalse(Forge121Api.needsOwnedWatcherCleanup("1.21.8", "58.1.22"));
        assertFalse(Forge121Api.needsOwnedWatcherCleanup("1.21.10", "60.1.15"));
        assertFalse(Forge121Api.needsOwnedWatcherCleanup("1.21.9", "159.0.5"));
        assertFalse(Forge121Api.needsOwnedWatcherCleanup("1.21.11", "59.0.5"));
    }

    @Test
    void legacyPermissionChecksLiveLevelTwoState() {
        var check = Forge121Api.gameMaster(LegacySource.class, Object.class);
        var source = new LegacySource();
        for (int level = 0; level < 5; level++) {
            source.level = level;
            assertEquals(level >= 2, check.test(source));
        }
        source.level = 0;
        assertFalse(check.test(source));
    }

    @Test
    void modernPermissionUsesOfficialGameMasterPredicate() {
        var check = Forge121Api.gameMaster(ModernSource.class, ModernCommands.class);
        var source = new ModernSource();
        assertFalse(check.test(source));
        source.allowed = true;
        assertTrue(check.test(source));
        source.allowed = false;
        assertFalse(check.test(source));
    }

    @Test
    void unavailableOrThrowingPermissionChecksFailClosed() {
        assertThrows(IllegalStateException.class, () -> Forge121Api.gameMaster(Object.class, Object.class));
        var check = Forge121Api.gameMaster(BrokenSource.class, ModernCommands.class);
        assertThrows(IllegalStateException.class, () -> check.test(new BrokenSource()));
        assertThrows(IllegalStateException.class, () -> Forge121Api.gameMaster(ModernSource.class, BrokenCommands.class));
    }

    @Test
    void supportsOldAndRecordProfileAccessors() {
        assertEquals("old", Forge121Api.profileName(LegacyProfile.class).apply(new LegacyProfile()));
        assertEquals("new", Forge121Api.profileName(RecordProfile.class).apply(new RecordProfile("new")));
        assertThrows(IllegalStateException.class, () -> Forge121Api.profileName(Object.class));
    }

    public static final class LegacySource {
        int level;
        public boolean hasPermission(int required) { return level >= required; }
    }
    public static final class BrokenSource {
        public boolean hasPermission(int required) { throw new IllegalStateException("Synthetic failure"); }
    }
    public static final class ModernSource { boolean allowed; }
    public static final class ModernCommands {
        public static final Object LEVEL_GAMEMASTERS = new Object();
        public static Predicate<ModernSource> hasPermission(Object permission) {
            assertSame(LEVEL_GAMEMASTERS, permission);
            return source -> source.allowed;
        }
    }
    public static final class BrokenCommands {
        public static final Object LEVEL_GAMEMASTERS = new Object();
        public static Predicate<ModernSource> hasPermission(Object permission) {
            throw new IllegalStateException("Synthetic factory failure");
        }
    }
    public static final class LegacyProfile { public String getName() { return "old"; } }
    public record RecordProfile(String name) {}
}
