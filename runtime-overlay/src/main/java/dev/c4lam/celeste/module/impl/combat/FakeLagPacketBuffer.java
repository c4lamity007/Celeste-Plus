package dev.c4lam.celeste.module.impl.combat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded latency for an unchanged outbound stream, not a packet-per-tick throttle. */
class FakeLagPacketBuffer extends ChannelDuplexHandler {
    static final long MAX_DELAY_MILLIS = 500L;
    private static final int MAX_PENDING_PACKETS = 512;

    private final Channel channel;
    private final LongSupplier clock;
    private final int maxPackets;
    private final Object snapshotLock = new Object();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private ChannelHandlerContext context;
    private ScheduledFuture<?> wakeup;
    private volatile boolean holding;
    private boolean stopped;
    private final java.util.concurrent.atomic.AtomicBoolean retirementRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> retirement = new java.util.concurrent.CompletableFuture<>();
    private long delayNanos;

    record Pending(Object packet, ChannelPromise promise, long enqueuedNanos, long dueNanos) { }

    FakeLagPacketBuffer(Channel channel) {
        this(channel, System::nanoTime, MAX_PENDING_PACKETS);
    }

    FakeLagPacketBuffer(Channel channel, LongSupplier clock, int maxPackets) {
        if (maxPackets < 1) throw new IllegalArgumentException("Positive queue capacity required");
        this.channel = channel;
        this.clock = clock;
        this.maxPackets = maxPackets;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
    }

    final boolean isHolding() {
        return holding;
    }

    final List<Pending> snapshot() {
        synchronized (snapshotLock) {
            return List.copyOf(pending);
        }
    }

    final void setHolding(boolean value, long delayMillis) {
        long boundedDelay = Math.max(0L, Math.min(MAX_DELAY_MILLIS, delayMillis));
        execute(() -> {
            if (stopped) return;
            if (!value || boundedDelay == 0L) {
                releaseNow();
            } else if (!holding) {
                // One latency per episode: do not randomly retime an existing queue.
                delayNanos = TimeUnit.MILLISECONDS.toNanos(boundedDelay);
                holding = true;
            }
        });
    }

    final void release() {
        execute(() -> { if (!stopped) releaseNow(); });
    }

    final void stop(boolean send) {
        if (!retirementRequested.compareAndSet(false, true)) return;
        dev.c4lam.celeste.beta.lifecycle.BetaNetworkRetirement.track(retirement);
        try {
            execute(() -> {
                try {
                    if (!stopped) {
                        stopped = true;
                        holding = false;
                        cancelWakeup();
                        if (send && channel.isActive()) drainAll();
                        else discardAll();
                    }
                    if (context != null && context.pipeline().context(this) != null) {
                        context.pipeline().remove(this);
                    }
                    // Leave removal and any handler callbacks before retiring payload bytes.
                    channel.eventLoop().execute(() -> retirement.complete(null));
                } catch (RuntimeException | LinkageError failure) {
                    retirement.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            retirement.completeExceptionally(failure);
        }
    }

    @Override
    public final void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        beforeWrite(message);
        if (stopped || !channel.isActive()) {
            discard(new Pending(message, promise, 0L, 0L));
            return;
        }
        if (!holding && peek() == null) {
            forward(message, promise);
            return;
        }
        if (size() >= maxPackets) {
            // This bound is enforced here, not by a later client tick. Never drop
            // actions or allow the newest packet to leap over the older stream.
            releaseNow();
            onForcedRelease();
            forward(message, promise);
            return;
        }
        long now = clock.getAsLong();
        synchronized (snapshotLock) {
            pending.addLast(new Pending(message, promise, now, now + (holding ? delayNanos : 0L)));
        }
        pump();
    }

    protected void beforeWrite(Object message) { }

    protected ChannelPromise downstreamPromise(Object message, ChannelPromise promise) {
        return promise;
    }

    protected void onForcedRelease() { }

    private void pump() {
        cancelWakeup();
        if (stopped) return;
        if (!channel.isActive()) {
            discardAll();
            return;
        }
        boolean wrote = false;
        Pending head;
        while ((head = peek()) != null && clock.getAsLong() - head.dueNanos() >= 0L) {
            poll();
            forward(head.packet(), head.promise());
            wrote = true;
        }
        if (wrote) context.flush();
        head = peek();
        if (head != null) {
            long remaining = Math.max(0L, head.dueNanos() - clock.getAsLong());
            wakeup = channel.eventLoop().schedule(() -> {
                wakeup = null;
                pump();
            }, remaining, TimeUnit.NANOSECONDS);
        }
    }

    private void releaseNow() {
        holding = false;
        cancelWakeup();
        // Explicitly removing latency necessarily permits catch-up. Keep the
        // complete original FIFO, including tick ends and acknowledgements.
        drainAll();
    }

    private void drainAll() {
        Pending entry;
        boolean wrote = false;
        while ((entry = poll()) != null) {
            forward(entry.packet(), entry.promise());
            wrote = true;
        }
        if (wrote && context != null) context.flush();
    }

    private void forward(Object message, ChannelPromise promise) {
        if (context == null || !channel.isActive()) {
            discard(new Pending(message, promise, 0L, 0L));
            return;
        }
        context.write(message, downstreamPromise(message, promise));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        stopped = true;
        holding = false;
        cancelWakeup();
        discardAll();
        super.channelInactive(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        stopped = true;
        holding = false;
        cancelWakeup();
        discardAll();
        this.context = null;
    }

    private void discardAll() {
        Pending entry;
        while ((entry = poll()) != null) discard(entry);
    }

    private static void discard(Pending entry) {
        ReferenceCountUtil.release(entry.packet());
        entry.promise().tryFailure(new ClosedChannelException());
    }

    private Pending peek() {
        synchronized (snapshotLock) { return pending.peekFirst(); }
    }

    private Pending poll() {
        synchronized (snapshotLock) { return pending.pollFirst(); }
    }

    private int size() {
        synchronized (snapshotLock) { return pending.size(); }
    }

    private void cancelWakeup() {
        if (wakeup != null) {
            wakeup.cancel(false);
            wakeup = null;
        }
    }

    private void execute(Runnable action) {
        // Both control changes and actual writes share this one serialization
        // point. A client-thread disable cannot expose a half-drained stream.
        if (channel.eventLoop().inEventLoop()) action.run();
        else channel.eventLoop().execute(action);
    }
}
