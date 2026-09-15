package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.function.Predicate;

final class NeoForgeCommandCompat {
    private NeoForgeCommandCompat() {
    }

    static Predicate<CommandSourceStack> requiresGameMaster() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }
}
