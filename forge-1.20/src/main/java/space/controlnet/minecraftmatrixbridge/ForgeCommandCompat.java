package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class ForgeCommandCompat {
    private ForgeCommandCompat() {
    }

    static ForgeMinecraftCompat minecraft() {
        var version = net.minecraftforge.fml.loading.FMLLoader.versionInfo();
        return Forge1206Reflection.supports(version.mcVersion(), version.forgeVersion())
                ? new Forge1206MinecraftCompat() : new ForgeMinecraftCompat();
    }

    static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        source.sendSuccess(() -> message, broadcastToOps);
    }

    static ForgeEventBus events() {
        return new ForgeEventBus();
    }
}
