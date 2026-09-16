package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.function.Consumer;

/** EventBus 7 path; never links against the removed EventBus 6 types. */
final class Forge121EventBus extends ForgeEventBus {
    private final ModernEventBus bus = ModernEventBus.load(getClass().getClassLoader());

    @Override
    void stopConfigWatcher() throws Exception {
        var version = net.minecraftforge.fml.loading.FMLLoader.versionInfo();
        if (Forge121Api.needsOwnedWatcherCleanup(version.mcVersion(), version.forgeVersion())) {
            // Forge 59 owns separate COMMON/SERVER watchers. Called only after a
            // dedicated server's thread exits, and only for affected NightConfig.
            var tracker = net.minecraftforge.fml.config.ConfigTracker.INSTANCE;
            tracker.getClass().getMethod("forceUnload").invoke(tracker);
        } else {
            super.stopConfigWatcher();
        }
    }

    @Override
    String eventName(String name) {
        return ModernEventBus.eventName(name);
    }

    @Override
    void register(ForgeHooks hooks) {
        subscribe(ServerStartedEvent.class, hooks::onServerStarted);
        subscribe(ServerStoppingEvent.class, hooks::onServerStopping);
        subscribe(ServerStoppedEvent.class, hooks::onServerStopped);
        subscribe(RegisterCommandsEvent.class, hooks::onRegisterCommands);
        subscribe(ServerChatEvent.class, hooks::onServerChat);
        subscribe(PlayerEvent.PlayerLoggedInEvent.class, hooks::onPlayerLoggedIn);
        subscribe(PlayerEvent.PlayerLoggedOutEvent.class, hooks::onPlayerLoggedOut);
        try {
            // In later 1.21 patches, the parent is an interface without phase or BUS.
            listen(Class.forName("net.minecraftforge.event.TickEvent$ServerTickEvent$Post"),
                    ignored -> hooks.onServerPostTick());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing Forge server post-tick event", e);
        }
    }

    private <T> void subscribe(Class<T> type, Consumer<T> listener) {
        listen(type, event -> listener.accept(type.cast(event)));
    }

    @Override
    Class<?> eventBaseClass() {
        return bus.eventBaseClass();
    }

    @Override
    boolean isEventClass(Class<?> type) {
        return bus.isEventClass(type);
    }

    @Override
    void listen(Class<?> type, Consumer<Object> listener) {
        bus.listen(type, listener);
    }
}
