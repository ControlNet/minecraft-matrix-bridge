package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class ForgeCommandCompat {
    private ForgeCommandCompat() {
    }

    static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        source.sendSuccess(message, broadcastToOps);
    }
}

