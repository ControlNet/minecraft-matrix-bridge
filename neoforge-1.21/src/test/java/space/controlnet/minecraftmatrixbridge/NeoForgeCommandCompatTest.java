package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeCommandCompatTest {
    // Synthetic API fixtures model the old integer and new permission-object contracts.
    public static class LegacySource {
        int level;
        public boolean hasPermission(int required) { return level >= required; }
    }

    public static class ModernSource {
        boolean gameMaster;
    }

    public record PermissionCheck(String name) {}

    public static class ModernCommands {
        public static final PermissionCheck LEVEL_GAMEMASTERS = new PermissionCheck("gamemaster");
        public static Predicate<ModernSource> hasPermission(PermissionCheck check) {
            assertSame(LEVEL_GAMEMASTERS, check);
            return source -> source.gameMaster;
        }
    }

    public static class BrokenLegacySource {
        public boolean hasPermission(int required) { throw new IllegalStateException("Permission provider failed"); }
    }

    public static class BrokenModernCommands {
        public static final PermissionCheck LEVEL_GAMEMASTERS = ModernCommands.LEVEL_GAMEMASTERS;
        public static Predicate<ModernSource> hasPermission(PermissionCheck check) {
            throw new IllegalStateException("Permission provider failed");
        }
    }

    public static class InvalidModernCommands {
        public static final PermissionCheck LEVEL_GAMEMASTERS = ModernCommands.LEVEL_GAMEMASTERS;
        public static Object hasPermission(PermissionCheck check) { return null; }
    }

    @Test
    void legacyRequiresLevelTwoAndRechecksCurrentPermission() {
        var predicate = NeoForgeCommandCompat.resolveGameMaster(LegacySource.class, Object.class);
        var source = new LegacySource();
        for (int level = 0; level <= 4; level++) {
            source.level = level;
            assertEquals(level >= 2, predicate.test(source));
        }
        source.level = 0;
        assertFalse(predicate.test(source));
    }

    @Test
    void modernUsesOfficialGameMasterPredicateAndRechecksPermission() {
        var predicate = NeoForgeCommandCompat.resolveGameMaster(ModernSource.class, ModernCommands.class);
        var source = new ModernSource();
        assertFalse(predicate.test(source));
        source.gameMaster = true;
        assertTrue(predicate.test(source));
        source.gameMaster = false;
        assertFalse(predicate.test(source));
    }

    @Test
    void missingApiFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> NeoForgeCommandCompat.resolveGameMaster(ModernSource.class, Object.class));
    }

    @Test
    void legacyInvocationFailureNeverFallsBackToGrantingAccess() {
        var predicate = NeoForgeCommandCompat.resolveGameMaster(BrokenLegacySource.class, ModernCommands.class);
        assertThrows(IllegalStateException.class, () -> predicate.test(new BrokenLegacySource()));
    }

    @Test
    void modernFactoryFailureFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> NeoForgeCommandCompat.resolveGameMaster(ModernSource.class, BrokenModernCommands.class));
    }

    @Test
    void invalidModernPredicateFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> NeoForgeCommandCompat.resolveGameMaster(ModernSource.class, InvalidModernCommands.class));
    }
}
