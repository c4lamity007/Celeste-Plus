package dev.c4lam.celeste.beta.memory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Paused real-loader visits prove read concurrency and exclusive retirement, without a game. */
public final class RetainedReadConcurrencyCheck {
    private static int checks;
    private static byte[] first, second;
    private RetainedReadConcurrencyCheck() { }

    public static void main(String[] args) throws Exception {
        var compiled = TwoPartMemoryClassLoaderTest.compile(Map.of("demo.payload.ReadDuringHash",
            "package demo.payload; public final class ReadDuringHash { public static int value() { return 42; } }"));
        first = TwoPartMemoryClassLoaderTest.jar(Map.of("assets/check/first", bytes("first retained"),
            "demo/payload/ReadDuringHash.class", compiled.get("demo.payload.ReadDuringHash")));
        second = TwoPartMemoryClassLoaderTest.jar(Map.of("assets/check/second", bytes("second retained")));
        boolean baseline = args.length == 1 && args[0].equals("baseline");
        readsDuringVisit(baseline);
        if (!baseline) {
            exclusiveClose();
            serializedVisits();
            nestedAndFailedVisits();
        }
        System.out.println("PASS: retained-byte lifetime concurrency (" + checks + " checks; baseline=" + baseline + ")");
    }

    private static void readsDuringVisit(boolean baseline) throws Exception {
        try (var loader = open()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
            CompletableFuture<Void> visiting = new CompletableFuture<>(), reading = new CompletableFuture<>();
            AtomicInteger parts = new AtomicInteger();
            Thread visitor = worker("paused-integrity-visitor", visiting, () -> loader.visitRetainedParts(part -> {
                if (parts.incrementAndGet() == 1) { entered.countDown(); await(release); }
                for (byte[] bytes : part.values()) check(bytes.length > 0 && bytes[0] != 0, "borrowed bytes remain intact");
            }));
            check(entered.await(3, TimeUnit.SECONDS), "visitor acquired its lifetime borrow");
            Thread reader = worker("concurrent-game-resource-read", reading, () -> {
                started.countDown();
                try (var stream = loader.getResourceAsStream("assets/check/second")) {
                    check(new String(stream.readAllBytes(), StandardCharsets.UTF_8).equals("second retained"), "resource bytes unchanged during visit");
                }
                check(loader.loadClass("demo.payload.ReadDuringHash").getMethod("value").invoke(null).equals(42),
                    "new class definition succeeds during visit");
                try (var stream = loader.getResource("assets/check/first").openStream()) {
                    check(stream.read() == 'f', "resource URL remains readable during visit");
                }
            });
            try {
                check(started.await(3, TimeUnit.SECONDS), "resource reader started");
                if (baseline) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (reader.getState() != Thread.State.BLOCKED && !reading.isDone() && System.nanoTime() < deadline) Thread.yield();
                    var info = ManagementFactory.getThreadMXBean().getThreadInfo(reader.threadId());
                    check(info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() == visitor.threadId(),
                        "baseline game read is blocked by paused integrity visitor's monitor");
                    System.out.println("BASELINE: unrelated class/resource read blocked by paused integrity visitor");
                } else {
                    reading.get(3, TimeUnit.SECONDS);
                    check(!visiting.isDone(), "all game reads finish while integrity visitor remains paused");
                }
            } finally { release.countDown(); }
            visiting.get(3, TimeUnit.SECONDS); reading.get(3, TimeUnit.SECONDS);
            check(parts.get() == 2, "exactly two immutable part callbacks");
        }
    }

    private static void exclusiveClose() throws Exception {
        var loader = open();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> visiting = new CompletableFuture<>(), closing = new CompletableFuture<>();
        var retained = new java.util.concurrent.CopyOnWriteArrayList<byte[]>();
        worker("closing-integrity-visitor", visiting, () -> loader.visitRetainedParts(part -> {
            retained.addAll(part.values());
            if (entered.getCount() != 0) { entered.countDown(); await(release); }
            for (byte[] bytes : part.values()) check(bytes[0] != 0, "close cannot wipe active visit bytes");
        }));
        check(entered.await(3, TimeUnit.SECONDS), "close fixture visit started");
        Thread closer = worker("retiring-memory-loader", closing, loader::close);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (closer.getState() != Thread.State.WAITING && !closing.isDone() && System.nanoTime() < deadline) Thread.yield();
            check(closer.getState() == Thread.State.WAITING && !closing.isDone(), "close waits for lifetime borrow");
            check(loader.isClosed(), "retirement immediately revokes new access");
            check(loader.getResourceAsStream("assets/check/first") == null, "retiring loader rejects new resources");
            reject(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.ReadDuringHash"));
            reject(IOException.class, () -> loader.visitRetainedParts(part -> { throw new AssertionError("retired callback ran"); }));
            closer.interrupt();
            check(!closing.isDone(), "interrupted close does not claim completion before wiping");
        } finally { release.countDown(); }
        visiting.get(3, TimeUnit.SECONDS); closing.get(3, TimeUnit.SECONDS);
        check(closer.isInterrupted(), "close restores interruption after required cleanup");
        for (byte[] bytes : retained) for (byte value : bytes) check(value == 0, "all borrowed bytes wiped after visit completes");
        check(loader.retainedByteCount() == 0 && loader.retainedEntryCount() == 0, "retirement clears accounting");
        loader.close();
    }

    private static void serializedVisits() throws Exception {
        try (var loader = open()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            CompletableFuture<Void> firstVisit = new CompletableFuture<>(), secondVisit = new CompletableFuture<>();
            AtomicInteger callbacks = new AtomicInteger();
            worker("first-integrity-pass", firstVisit, () -> loader.visitRetainedParts(part -> { entered.countDown(); await(release); }));
            check(entered.await(3, TimeUnit.SECONDS), "first concurrent visit started");
            Thread queued = worker("second-integrity-pass", secondVisit, () -> loader.visitRetainedParts(part -> callbacks.incrementAndGet()));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (queued.getState() != Thread.State.WAITING && !secondVisit.isDone() && System.nanoTime() < deadline) Thread.yield();
                check(queued.getState() == Thread.State.WAITING && callbacks.get() == 0, "concurrent fresh visits serialize without failing");
                try (var read = loader.getResourceAsStream("assets/check/first")) { check(read.read() == 'f', "queued integrity pass never blocks game resource access"); }
            } finally { release.countDown(); }
            firstVisit.get(3, TimeUnit.SECONDS); secondVisit.get(3, TimeUnit.SECONDS);
            check(callbacks.get() == 2, "queued visit receives both parts");
        }
    }

    private static void nestedAndFailedVisits() throws Exception {
        try (var loader = open()) {
            loader.visitRetainedParts(part -> {
                reject(IOException.class, () -> loader.visitRetainedParts(nested -> { }));
                reject(IllegalStateException.class, loader::close);
                check(!loader.isClosed(), "reentrant close must not begin retirement");
            });
            reject(IOException.class, () -> loader.visitRetainedParts(part -> { throw new IOException("synthetic callback failure"); }));
            AtomicInteger parts = new AtomicInteger();
            loader.visitRetainedParts(part -> parts.incrementAndGet());
            check(parts.get() == 2, "visitor failure releases lifetime borrow");
        }
    }

    private static TwoPartMemoryClassLoader open() throws Exception {
        return TwoPartMemoryClassLoader.open(first, hash(first), second, hash(second),
            new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("assets/check/")),
            TwoPartMemoryClassLoader.Limits.betaDefaults(), RetainedReadConcurrencyCheck.class.getClassLoader());
    }
    private static Thread worker(String name, CompletableFuture<Void> result, Action action) {
        return Thread.ofPlatform().daemon(true).name(name).start(() -> {
            try { action.run(); result.complete(null); } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture release timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static synchronized void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void reject(Class<? extends Throwable> type, Action action) throws IOException {
        try { action.run(); }
        catch (Throwable error) { check(type.isInstance(error), "expected " + type.getName() + ", got " + error); return; }
        throw new AssertionError("expected " + type.getName());
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
