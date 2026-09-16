package space.controlnet.minecraftmatrixbridge;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;
import java.util.function.Function;
import java.util.function.Predicate;

final class Forge121MinecraftCompat extends ForgeMinecraftCompat {
    private final Predicate<CommandSourceStack> gameMaster =
            Forge121Api.gameMaster(CommandSourceStack.class, Commands.class);
    private final Function<GameProfile, String> profileName = Forge121Api.profileName(GameProfile.class);

    @Override
    boolean hasPermission(CommandSourceStack source, int level) {
        if (level != 2) {
            throw new IllegalArgumentException("Only the bridge's Game Master requirement is supported");
        }
        return gameMaster.test(source);
    }

    @Override
    String playerName(Player player) {
        return profileName.apply(player.getGameProfile());
    }
}
