import dev.c4lam.celeste.beta.lifecycle.BetaModelImports;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual resident scope/import classes; no game, credentials or synthetic production receipts. */
public final class ModelImportScopeCheck {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static Runnable queued(BlockingQueue<Runnable> queue) throws Exception {
        Runnable next = queue.poll(5, TimeUnit.SECONDS);
        if (next == null) throw new AssertionError("Model result did not queue");
        return next;
    }
    public static void main(String[] ignored) throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var installed = new AtomicInteger();
        var normal = new ModuleScope();
        try (var ownership = normal.enter()) {
            BetaModelImports.submit(() -> "ready", queue::add, (value, failure) -> {
                check(failure == null && value.equals("ready"), "Unchanged result delivered");
                check(ModuleScope.current() == normal, "Install carries module ownership");
                installed.incrementAndGet();
            });
        }
        queued(queue).run();
        normal.close();
        normal.retired().toCompletableFuture().get(5, TimeUnit.SECONDS);
        check(installed.get() == 1, "Ordinary import installs exactly once");

        var deferred = new ModuleScope();
        try (var ownership = deferred.enter()) {
            BetaModelImports.submit(() -> "late", queue::add, (value, failure) -> installed.incrementAndGet());
        }
        Runnable stale = queued(queue);
        deferred.close();
        check(!deferred.retired().toCompletableFuture().isDone(), "Queued callback holds loader until removed");
        stale.run();
        deferred.retired().toCompletableFuture().get(5, TimeUnit.SECONDS);
        check(installed.get() == 1, "Retired callback cannot reinstall model");

        var parsing = new ModuleScope();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var ownership = parsing.enter()) {
            BetaModelImports.submit(() -> {
                check(ModuleScope.current() == parsing, "Parse carries module ownership");
                started.countDown();
                try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("parse timeout"); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                return "late";
            }, queue::add, (value, failure) -> installed.incrementAndGet());
        }
        check(started.await(5, TimeUnit.SECONDS), "Worker began");
        parsing.close();
        check(!parsing.retired().toCompletableFuture().isDone(), "Retirement awaits actual running parser");
        finish.countDown();
        parsing.retired().toCompletableFuture().get(5, TimeUnit.SECONDS);
        check(queue.isEmpty(), "Retired parse does not queue a new child callback");
        check(installed.get() == 1, "Retired parse cannot install");

        var rejected = new ModuleScope();
        rejected.close();
        try { rejected.enter(); throw new AssertionError("Retired scope entered"); }
        catch (IllegalStateException expected) { checks++; }

        BetaModelImports.submit(() -> "legacy", queue::add, (value, failure) -> installed.incrementAndGet());
        queued(queue).run();
        check(installed.get() == 2, "Unscoped legacy host still installs");

        var failed = new ModuleScope();
        try (var ownership = failed.enter()) {
            BetaModelImports.submit(() -> "result", queue::add, (value, failure) -> { throw new IllegalStateException("fixture failure"); });
        }
        queued(queue).run();
        failed.close();
        try { failed.retired().toCompletableFuture().get(5, TimeUnit.SECONDS); throw new AssertionError("Failed callback retired cleanly"); }
        catch (ExecutionException expected) { check(expected.getCause() != null, "Failed callback quarantines retirement"); }
        System.out.println("PASS model import scope: 6 cases / " + checks + " assertions");
    }
}
