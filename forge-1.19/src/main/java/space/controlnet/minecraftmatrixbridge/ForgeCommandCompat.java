package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class ForgeCommandCompat {
    private ForgeCommandCompat() {
    }

    static ForgeMinecraftCompat minecraft() {
        return new ForgeMinecraftCompat();
    }

    static ForgeEventBus events() {
        return new ForgeEventBus();
    }

    static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        source.sendSuccess(message, broadcastToOps);
    }
}
