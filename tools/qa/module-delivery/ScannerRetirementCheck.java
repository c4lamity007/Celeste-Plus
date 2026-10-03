package fixture;
import dev.c4lam.celeste.module.impl.visual.SuspiciousChunkFinderFeature;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual scanner retirement with controlled worker termination; no world or GPU. */
public final class ScannerRetirementCheck {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Field field(String name) throws Exception {
        Field value = SuspiciousChunkFinderFeature.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    public static void main(String[] ignored) throws Exception {
        for (int cycle=0;cycle<4;cycle++) {
            var feature = new SuspiciousChunkFinderFeature();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "scanner-retirement-fixture");
                thread.setDaemon(true);
                thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
                return thread;
            });
            Future<?> task = worker.submit(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException expected) { interrupted.set(true); }
                }
            });
            check(started.await(3,TimeUnit.SECONDS), "worker must start");
            field("worker").set(feature,worker);
            field("future").set(feature,task);
            field("enabled").setBoolean(feature,true);
            field("active").set(null,feature);
            @SuppressWarnings("unchecked") Map<Long,Long> revisions=(Map<Long,Long>)field("revisions").get(feature);
            revisions.put(42L,7L);
            long generation=field("generation").getLong(feature);
            CompletableFuture<Void> retired;
            try {
                long before=System.nanoTime();
                retired=feature.retire().toCompletableFuture();
                check(System.nanoTime()-before < TimeUnit.SECONDS.toNanos(1),"retirement must not block owner");
                check(!retired.isDone(),"retirement must wait for worker exit");
                check(worker.isShutdown(),"worker stops accepting jobs");
                check(field("active").get(null)==null && !field("enabled").getBoolean(feature),"dispatch stopped");
                check(field("worker").get(feature)==null && field("future").get(feature)==null,"feature releases worker/task handles");
                check(field("generation").getLong(feature)>generation,"late result generation invalidated");
                check(revisions.isEmpty(),"captured revisions cleared");
                check(((List<?>)field("displayed").get(feature)).isEmpty() &&
                    ((List<?>)field("displayedPaths").get(feature)).isEmpty() &&
                    ((List<?>)field("displayedActivity").get(feature)).isEmpty(),"render snapshots cleared");
                CompletableFuture<Void> again=feature.retire().toCompletableFuture();
                check(!again.isDone(),"repeat retirement retains original drain");
                release.countDown();
                retired.get(3,TimeUnit.SECONDS);
                again.get(3,TimeUnit.SECONDS);
                check(worker.isTerminated() && interrupted.get(),"worker actually exited after cancellation");
                check(feature.retire().toCompletableFuture().isDone(),"completed retirement is idempotent");
            } finally {
                release.countDown();
                worker.shutdownNow();
                worker.awaitTermination(3,TimeUnit.SECONDS);
            }
        }
        System.out.println("{\"checks\":"+checks+",\"cycles\":4,\"world_access\":false,\"gpu\":false}");
    }
}
