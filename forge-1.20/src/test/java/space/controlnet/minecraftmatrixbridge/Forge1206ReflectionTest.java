package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class Forge1206ReflectionTest {
    @Test
    void modernAdapterOverridesEveryLegacyOperation() throws NoSuchMethodException {
        for (Method legacy : ForgeMinecraftCompat.class.getDeclaredMethods()) {
            Method modern = Forge1206MinecraftCompat.class.getDeclaredMethod(legacy.getName(), legacy.getParameterTypes());
            assertEquals(legacy.getReturnType(), modern.getReturnType());
        }
    }

    @Test
    void selectsOnlyMinecraft1206AndForge50() {
        assertTrue(Forge1206Reflection.supports("1.20.6", "50.0.0"));
        assertTrue(Forge1206Reflection.supports("1.20.6", "50.2.10"));
        for (String minecraft : new String[]{"1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.21", "26.1"}) {
            assertFalse(Forge1206Reflection.supports(minecraft, "50.2.10"));
        }
        for (String forge : new String[]{"47.2.0", "49.2.9", "51.0.33", "500.2.10", "50.invalid"}) {
            assertFalse(Forge1206Reflection.supports("1.20.6", forge));
        }
    }

    @Test
    void cachedMethodPreservesPermissionLevelsAndRevocation() {
        Method check = Forge1206Reflection.method(SyntheticSource.class, "hasPermission", boolean.class, false, int.class);
        SyntheticSource source = new SyntheticSource();
        for (int level = 0; level <= 4; level++) {
            source.level = level;
            assertEquals(level >= 2, Forge1206Reflection.invoke(check, source, 2));
        }
        source.level = 0;
        assertEquals(false, Forge1206Reflection.invoke(check, source, 2));
    }

    @Test
    void missingOrChangedSignaturesFailClosed() {
        assertThrows(IllegalStateException.class, () -> Forge1206Reflection.method(
                SyntheticSource.class, "missing", boolean.class, false, int.class));
        assertThrows(IllegalStateException.class, () -> Forge1206Reflection.method(
                SyntheticSource.class, "hasPermission", boolean.class, false, String.class));
        assertThrows(IllegalStateException.class, () -> Forge1206Reflection.method(
                SyntheticSource.class, "hasPermission", String.class, false, int.class));
        assertThrows(IllegalStateException.class, () -> Forge1206Reflection.method(
                SyntheticSource.class, "hasPermission", boolean.class, true, int.class));
    }

    @Test
    void permissionProviderFailureNeverGrantsAccess() {
        Method check = Forge1206Reflection.method(SyntheticSource.class, "brokenPermission", boolean.class, false, int.class);
        assertThrows(IllegalStateException.class, () -> Forge1206Reflection.invoke(check, new SyntheticSource(), 2));
    }

    @Test
    void staticFactoriesAndVoidCallsPreserveArguments() {
        Method factory = Forge1206Reflection.method(SyntheticSource.class, "literal", String.class, true, String.class);
        assertEquals("test", Forge1206Reflection.invoke(factory, null, "test"));
        Method send = Forge1206Reflection.method(SyntheticSource.class, "send", void.class, false, String.class, boolean.class);
        SyntheticSource source = new SyntheticSource();
        Forge1206Reflection.invoke(send, source, "message", true);
        assertEquals("message", source.message);
        assertTrue(source.broadcast);
    }

    /** Synthetic API fixture, not a Minecraft player or permission implementation. */
    public static final class SyntheticSource {
        int level;
        String message;
        boolean broadcast;

        public boolean hasPermission(int required) {
            return level >= required;
        }

        public boolean brokenPermission(int required) {
            throw new IllegalStateException("Synthetic provider failure");
        }

        public static String literal(String value) {
            return value;
        }

        public void send(String value, boolean toOps) {
            message = value;
            broadcast = toOps;
        }
    }
}
