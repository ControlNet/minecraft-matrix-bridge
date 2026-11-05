package space.controlnet.minecraftmatrixbridge;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic player stand-ins (test-only) for each runtime naming scheme. */
class ViewDistanceLookupTest {
    public static class MojmapPlayer {
        public int requestedViewDistance() { return 12; }
    }

    /** Forge 1.20.2-1.20.4 runtimes use SRG names; m_295486_ is requestedViewDistance there. */
    public static class SrgPlayer {
        public int m_295486_() { return 12; }
    }

    public static class OldPlayer {
    }

    @Test
    void findsTheOfficialName() throws Exception {
        Method method = PlayerViewDistance.findMethod(MojmapPlayer.class);
        assertEquals(12, method.invoke(new MojmapPlayer()));
    }

    @Test
    void findsTheSrgNameOnForge1202To1204() throws Exception {
        Method method = PlayerViewDistance.findMethod(SrgPlayer.class);
        assertNotNull(method, "1.20.2-1.20.4 players would always wait for the English timeout");
        assertEquals(12, method.invoke(new SrgPlayer()));
    }

    @Test
    void versionsWithoutTheMethodFallBack() {
        assertNull(PlayerViewDistance.findMethod(OldPlayer.class));
    }
}
