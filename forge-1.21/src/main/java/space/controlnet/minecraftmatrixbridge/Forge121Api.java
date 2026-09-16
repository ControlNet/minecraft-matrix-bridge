package space.controlnet.minecraftmatrixbridge;

import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.Predicate;

/** Late 1.21 API changes, resolved once without caching permission decisions. */
final class Forge121Api {
    private Forge121Api() {}

    static boolean needsOwnedWatcherCleanup(String minecraft, String forge) {
        return "1.21.9".equals(minecraft) && forge.startsWith("59.");
    }

    static <S> Predicate<S> gameMaster(Class<S> sourceType, Class<?> commandsType) {
        try {
            Method permission = sourceType.getMethod("hasPermission", int.class);
            if (permission.getReturnType() != boolean.class) {
                throw new IllegalStateException("Unexpected permission return type");
            }
            return source -> (boolean) invoke(permission, source, 2);
        } catch (NoSuchMethodException absent) {
            return modernGameMaster(commandsType);
        }
    }

    @SuppressWarnings("unchecked")
    private static <S> Predicate<S> modernGameMaster(Class<?> commandsType) {
        try {
            var level = commandsType.getField("LEVEL_GAMEMASTERS");
            Method factory = commandsType.getMethod("hasPermission", level.getType());
            Object result = invoke(factory, null, level.get(null));
            if (!(result instanceof Predicate<?> predicate)) {
                throw new IllegalStateException("Game Master factory did not return a predicate");
            }
            return (Predicate<S>) predicate;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Game Master permission API", e);
        }
    }

    static <P> Function<P, String> profileName(Class<P> profileType) {
        Method accessor;
        try {
            try {
                accessor = profileType.getMethod("getName");
            } catch (NoSuchMethodException recordProfile) {
                accessor = profileType.getMethod("name");
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unsupported player profile API", e);
        }
        if (accessor.getReturnType() != String.class) {
            throw new IllegalStateException("Unexpected player name return type");
        }
        Method resolved = accessor;
        return profile -> (String) invoke(resolved, profile);
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException e) {
            // Provider failures must not trigger a permissive fallback.
            throw new IllegalStateException("Forge 1.21 API call failed: " + method, e);
        }
    }
}
