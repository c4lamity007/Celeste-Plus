package net.minecraft.client;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Deterministic owner-thread executor; no game or native code is loaded. */
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    private final Thread owner = Thread.currentThread();
    private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();

    private MinecraftClient() { }

    public static MinecraftClient getInstance() { return INSTANCE; }
    public boolean isOnThread() { return Thread.currentThread() == owner; }

    public void execute(Runnable task) {
        if (isOnThread()) task.run();
        else queued.add(task);
    }

    public int queuedCount() { return queued.size(); }

    public void drain() {
        if (!isOnThread()) throw new AssertionError("Client queue drained off owner thread");
        Runnable task;
        while ((task = queued.poll()) != null) task.run();
    }
}
