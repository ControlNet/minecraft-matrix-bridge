package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.lang.reflect.Method;
import java.util.function.Predicate;

final class NeoForgeCommandCompat {
    private NeoForgeCommandCompat() {
    }

    static Predicate<CommandSourceStack> requiresGameMaster() {
        return GameMasterRequirement.PREDICATE;
    }

    private static final class GameMasterRequirement {
        // Resolve once per runtime, never cache a user's authorization result.
        static final Predicate<CommandSourceStack> PREDICATE =
                resolveGameMaster(CommandSourceStack.class, Commands.class);
    }

    static <S> Predicate<S> resolveGameMaster(Class<S> sourceType, Class<?> commandsType) {
        final Method legacyCheck;
        try {
            legacyCheck = sourceType.getMethod("hasPermission", int.class);
        } catch (NoSuchMethodException absentOnNewRuntime) {
            return resolveModernGameMaster(commandsType);
        }
        if (legacyCheck.getReturnType() != boolean.class) {
            throw new IllegalStateException("Unexpected hasPermission(int) return type");
        }
        return source -> {
            try {
                return (boolean) legacyCheck.invoke(source, 2);
            } catch (ReflectiveOperationException e) {
                // A broken permission provider must not grant access or trigger a fallback.
                throw new IllegalStateException("Could not check level-2 command permission", e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <S> Predicate<S> resolveModernGameMaster(Class<?> commandsType) {
        try {
            // 1.21.11 changed LEVEL_GAMEMASTERS from int to PermissionCheck.
            // Reflect only at the API boundary so the JAR still loads on 1.21-1.21.10.
            var level = commandsType.getField("LEVEL_GAMEMASTERS");
            var factory = commandsType.getMethod("hasPermission", level.getType());
            Object result = factory.invoke(null, level.get(null));
            if (!(result instanceof Predicate<?> predicate)) {
                throw new IllegalStateException("Game Master permission factory did not return a predicate");
            }
            return (Predicate<S>) predicate;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Game Master command permission API", e);
        }
    }
}
