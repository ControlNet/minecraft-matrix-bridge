package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import java.util.function.Consumer;

/** Legacy bus operations are only executed on EventBus 6 runtimes. */
class ForgeEventBus {
    void stopConfigWatcher() throws Exception {
        com.electronwill.nightconfig.core.file.FileWatcher.defaultInstance().stop();
    }

    String eventName(String name) {
        return name;
    }

    void register(ForgeHooks hooks) {
        MinecraftForge.EVENT_BUS.register(hooks);
    }

    Class<?> eventBaseClass() {
        return Event.class;
    }

    boolean isEventClass(Class<?> type) {
        return eventBaseClass().isAssignableFrom(type);
    }

    @SuppressWarnings("unchecked")
    void listen(Class<?> type, Consumer<Object> listener) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) type, listener::accept);
    }
}
