package space.controlnet.minecraftmatrixbridge;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static space.controlnet.minecraftmatrixbridge.Forge1206Reflection.invoke;
import static space.controlnet.minecraftmatrixbridge.Forge1206Reflection.method;

/** Only loaded for MC 1.20.6 / Forge 50; names stay in Mojmap when the JAR is reobfuscated. */
final class Forge1206MinecraftCompat extends ForgeMinecraftCompat {
    private final Method permission = method(CommandSourceStack.class, "hasPermission", boolean.class, false, int.class);
    private final Method literal = method(Component.class, "literal", MutableComponent.class, true, String.class);
    private final Method success = method(CommandSourceStack.class, "sendSuccess", void.class, false, Supplier.class, boolean.class);
    private final Method failure = method(CommandSourceStack.class, "sendFailure", void.class, false, Component.class);
    private final Method server = method(CommandSourceStack.class, "getServer", MinecraftServer.class, false);
    private final Method worldPath = method(MinecraftServer.class, "getWorldPath", Path.class, false, LevelResource.class);
    private final Method dedicated = method(MinecraftServer.class, "isDedicatedServer", boolean.class, false);
    private final Method playerList = method(MinecraftServer.class, "getPlayerList", PlayerList.class, false);
    private final Method players = method(PlayerList.class, "getPlayers", List.class, false);
    private final Method player = method(PlayerList.class, "getPlayer", ServerPlayer.class, false, UUID.class);
    private final Method profile = method(Player.class, "getGameProfile", GameProfile.class, false);
    private final Method uuid = method(ServerPlayer.class, "getUUID", UUID.class, false);
    private final Method message = method(ServerPlayer.class, "sendSystemMessage", void.class, false, Component.class);
    private final Method broadcast = method(PlayerList.class, "broadcastSystemMessage", void.class, false, Component.class, boolean.class);
    private final LevelResource root;

    Forge1206MinecraftCompat() {
        try {
            root = LevelResource.class.cast(LevelResource.class.getField("ROOT").get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Missing Forge 1.20.6 world root", e);
        }
    }

    @Override
    LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    @Override
    <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    @Override
    boolean hasPermission(CommandSourceStack source, int level) {
        return (boolean) invoke(permission, source, level);
    }

    @Override
    Component text(String value) {
        return (Component) invoke(literal, null, value);
    }

    @Override
    void sendSuccess(CommandSourceStack source, Component value, boolean broadcastToOps) {
        invoke(success, source, (Supplier<Component>) () -> value, broadcastToOps);
    }

    @Override
    void sendFailure(CommandSourceStack source, Component value) {
        invoke(failure, source, value);
    }

    @Override
    MinecraftServer server(CommandSourceStack source) {
        return (MinecraftServer) invoke(server, source);
    }

    @Override
    Path worldRoot(MinecraftServer value) {
        return (Path) invoke(worldPath, value, root);
    }

    @Override
    boolean isDedicatedServer(MinecraftServer value) {
        return (boolean) invoke(dedicated, value);
    }

    @Override
    List<ServerPlayer> players(MinecraftServer value) {
        // Validate the generic elements without an unchecked cast.
        return ((List<?>) invoke(players, invoke(playerList, value))).stream()
                .map(ServerPlayer.class::cast).toList();
    }

    @Override
    ServerPlayer player(MinecraftServer value, UUID id) {
        return (ServerPlayer) invoke(player, invoke(playerList, value), id);
    }

    @Override
    String playerName(Player value) {
        return ((GameProfile) invoke(profile, value)).getName();
    }

    @Override
    UUID playerId(ServerPlayer value) {
        return (UUID) invoke(uuid, value);
    }

    @Override
    void sendPlayerMessage(ServerPlayer value, Component text) {
        invoke(message, value, text);
    }

    @Override
    void broadcast(MinecraftServer value, String text) {
        invoke(broadcast, invoke(playerList, value), text(text), false);
    }
}
