package net.minecraft.client;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Deterministic owner-thread executor; no game or native code is loaded. */
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    private final Thread owner = Thread.currentThread();
    private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
    public boolean focused=true, hudHidden, menuOpen;
    public boolean isWindowFocused(){return focused;}
    public Window getWindow(){return new Window();}
    public static final class Window {
        public long getHandle(){return 1234;}
        public int getFramebufferWidth(){return 1920;}
        public int getFramebufferHeight(){return 1080;}
        public int getScaledWidth(){return 960;}
        public int getScaledHeight(){return 540;}
    }
    public final java.util.concurrent.atomic.AtomicInteger scheduledStops = new java.util.concurrent.atomic.AtomicInteger();
    public void scheduleStop() {
        if(!isOnThread())throw new AssertionError("Stop scheduled off owner thread");
        scheduledStops.incrementAndGet();
    }

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
