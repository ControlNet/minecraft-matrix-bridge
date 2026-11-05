package space.controlnet.minecraftmatrixbridge;

import java.lang.reflect.Method;

/** Finds the player's requested view distance accessor; free of Minecraft types for testing. */
final class PlayerViewDistance {
    /**
     * {@code ServerPlayer.requestedViewDistance()} exists from 1.20.2; Forge 1.20.2-1.20.4
     * runtimes use its SRG name {@code m_295486_} (checked against the 1.20.4 mappings).
     * Earlier versions have no equivalent, so callers fall back to the language heuristic.
     */
    private static final String[] NAMES = {"requestedViewDistance", "getRequestedViewDistance", "m_295486_"};

    private PlayerViewDistance() {
    }

    static Method findMethod(Class<?> playerType) {
        for (String name : NAMES) {
            try {
                Method method = playerType.getMethod(name);
                if (method.getReturnType() == int.class) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Try the next version-specific name.
            }
        }
        return null;
    }
}
