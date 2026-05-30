package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

final class NeoForgeCommandCompat {
    private NeoForgeCommandCompat() {
    }

    static Predicate<CommandSourceStack> requiresGameMaster() {
        return source -> source.hasPermission(2);
    }
}
