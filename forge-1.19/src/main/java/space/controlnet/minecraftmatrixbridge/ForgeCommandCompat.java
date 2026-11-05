package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class ForgeCommandCompat {
    private ForgeCommandCompat() {
    }

    static ForgeMinecraftCompat minecraft() {
        // Minecraft 1.19.0 (Forge 41) lacks APIs this module links directly.
        String minecraft = net.minecraftforge.fml.loading.FMLLoader.versionInfo().mcVersion();
        return Forge1190MinecraftCompat.supports(minecraft) ? new Forge1190MinecraftCompat() : new ForgeMinecraftCompat();
    }

    static ForgeEventBus events() {
        return new ForgeEventBus();
    }

    static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        source.sendSuccess(message, broadcastToOps);
    }
}
