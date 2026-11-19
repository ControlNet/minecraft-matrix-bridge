package space.controlnet.minecraftmatrixbridge;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Minecraft 1.19 / Forge 41 adapter.
 *
 * <p>The module compiles against 1.19.2, but 1.19.0 predates two APIs that the
 * shared code links directly: {@code PlayerList.broadcastSystemMessage(Component, boolean)}
 * (added in 1.19.1) and {@code ServerChatEvent.getMessage()} returning a Component
 * (Forge 41 returns the raw String and broadcasts {@code getComponent()}). This adapter is only selected on 1.19.0, where
 * the runtime uses SRG names, so the members are resolved by their SRG names.</p>
 */
final class Forge1190MinecraftCompat extends ForgeMinecraftCompat {
    /** {@code PlayerList.broadcastSystemMessage(Component, ResourceKey<ChatType>)} in 1.19.0. */
    static final String BROADCAST_SYSTEM_MESSAGE = "m_215627_";
    /** {@code ChatType.SYSTEM} in 1.19.0. */
    static final String CHAT_TYPE_SYSTEM = "f_130599_";

    private final Method broadcast;
    private final Object systemChatType;
    private final Method chatMessage;
    private final Method rawMessage;
    private final CommandEchoes commandEchoes = new CommandEchoes();

    Forge1190MinecraftCompat() {
        this(PlayerList.class, ChatType.class, ServerChatEvent.class);
        // Fires synchronously with the whole command line, before the message argument is
        // decorated (asynchronously) and ServerChatEvent fires for it.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, CommandEvent.class,
                event -> commandEchoes.noteCommand(event.getParseResults().getReader().getString(),
                        System.currentTimeMillis()));
    }

    Forge1190MinecraftCompat(Class<?> playerList, Class<?> chatType, Class<?> chatEvent) {
        try {
            broadcast = playerList.getMethod(BROADCAST_SYSTEM_MESSAGE, Component.class, ResourceKey.class);
            if (broadcast.getReturnType() != void.class || Modifier.isStatic(broadcast.getModifiers())) {
                throw new IllegalStateException("Unexpected Minecraft 1.19 broadcast signature: " + broadcast);
            }
            var field = chatType.getField(CHAT_TYPE_SYSTEM);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != ResourceKey.class) {
                throw new IllegalStateException("Unexpected Minecraft 1.19 chat type field: " + field);
            }
            systemChatType = field.get(null);
            // getMessage() is the text as typed; getComponent() is what Forge broadcasts after
            // other mods filtered or cleared it, so relay that.
            chatMessage = chatEvent.getMethod("getComponent");
            rawMessage = chatEvent.getMethod("getMessage");
            if (rawMessage.getReturnType() != String.class) {
                throw new IllegalStateException("Unexpected Forge 41 chat event: " + rawMessage);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Missing Minecraft 1.19 / Forge 41 API", e);
        }
    }

    static boolean supports(String minecraft) {
        return "1.19".equals(minecraft);
    }

    @Override
    void broadcast(MinecraftServer server, String message) {
        invoke(broadcast, server.getPlayerList(), text(message), systemChatType);
    }

    @Override
    String chatMessage(ServerChatEvent event) {
        // Forge 41 also fires ServerChatEvent for the text of /say, /me, /msg and /teammsg, before
        // the command picks its recipients (and with no player for the console). Only chat typed
        // into the chat box is relayed, so private messages stay private.
        if (event.getPlayer() == null
                || commandEchoes.consume((String) invoke(rawMessage, event), System.currentTimeMillis())) {
            return "";
        }
        return chatText(invoke(chatMessage, event));
    }

    /** Text of the broadcast chat component; empty when a mod removed the message. */
    static String chatText(Object message) {
        if (message instanceof String text) {
            return text;
        }
        if (message instanceof Component component) {
            return component.getString();
        }
        return message == null ? "" : message.toString();
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Minecraft 1.19 API call failed: " + method, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Minecraft 1.19 API call failed: " + method, e);
        }
    }
}
