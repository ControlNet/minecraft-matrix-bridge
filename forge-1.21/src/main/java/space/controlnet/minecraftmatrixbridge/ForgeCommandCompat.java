package space.controlnet.minecraftmatrixbridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class ForgeCommandCompat {
    private ForgeCommandCompat() {
    }

    static ForgeMinecraftCompat minecraft() {
        return usesModernBus() ? new Forge121MinecraftCompat() : new ForgeMinecraftCompat();
    }

    static ForgeEventBus events() {
        return usesModernBus() ? new Forge121EventBus() : new ForgeEventBus();
    }

    private static boolean usesModernBus() {
        int forgeMajor = Integer.parseInt(net.minecraftforge.fml.loading.FMLLoader.versionInfo().forgeVersion().split("\\.")[0]);
        return forgeMajor >= 56;
    }

    static void sendSuccess(CommandSourceStack source, Component message, boolean broadcastToOps) {
        source.sendSuccess(() -> message, broadcastToOps);
    }
}
