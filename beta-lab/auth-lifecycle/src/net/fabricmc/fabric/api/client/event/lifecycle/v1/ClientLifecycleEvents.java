package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.minecraft.client.MinecraftClient;

public final class ClientLifecycleEvents {
    public static final Event<ClientStarted> CLIENT_STARTED = new Event<>(ClientStarted::onClientStarted);
    public static final Event<ClientStopping> CLIENT_STOPPING = new Event<>(ClientStopping::onClientStopping);

    @FunctionalInterface public interface ClientStarted { void onClientStarted(MinecraftClient client); }
    @FunctionalInterface public interface ClientStopping { void onClientStopping(MinecraftClient client); }

    private ClientLifecycleEvents() { }

    public static final class Event<T> {
        private final CopyOnWriteArrayList<T> listeners = new CopyOnWriteArrayList<>();
        private final BiConsumer<T, MinecraftClient> dispatch;
        Event(BiConsumer<T, MinecraftClient> dispatch) { this.dispatch = dispatch; }
        public void register(T listener) { listeners.add(listener); }
        public int size() { return listeners.size(); }
        public void fire(MinecraftClient client) {
            for (T listener : listeners) dispatch.accept(listener, client);
        }
    }
}
