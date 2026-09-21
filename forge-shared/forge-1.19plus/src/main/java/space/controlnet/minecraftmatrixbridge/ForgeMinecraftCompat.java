package space.controlnet.minecraftmatrixbridge;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Default adapter preserves the module's existing mapped, direct calls. */
class ForgeMinecraftCompat {
    LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }

    boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    Component text(String value) {
        return Component.literal(value);
    }

    void sendSuccess(CommandSourceStack source, Component message, boolean broadcast) {
        ForgeCommandCompat.sendSuccess(source, message, broadcast);
    }

    void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    MinecraftServer server(CommandSourceStack source) {
        return source.getServer();
    }

    Path worldRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    boolean isDedicatedServer(MinecraftServer server) {
        return server.isDedicatedServer();
    }

    List<ServerPlayer> players(MinecraftServer server) {
        return server.getPlayerList().getPlayers();
    }

    ServerPlayer player(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    String playerName(Player player) {
        return player.getGameProfile().getName();
    }

    UUID playerId(ServerPlayer player) {
        return player.getUUID();
    }

    void sendPlayerMessage(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(text(message), false);
    }
}
