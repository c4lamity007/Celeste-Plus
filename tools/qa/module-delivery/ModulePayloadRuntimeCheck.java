package fixture;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ModulePayloadRuntimeCheck {
    public interface Tick { void fire(); default int identity() { return 7; } }
    static int assertions, cases;
    static byte[] childBytes;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static void reject(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); }
        catch (IllegalArgumentException | IllegalStateException expected) { assertions++; }
    }
    static final class Isolated extends ClassLoader {
        Isolated() { super(ModuleFeature.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("fixture.ChildFeature")) return super.loadClass(name, resolve);
            Class<?> value = findLoadedClass(name);
            if (value == null) value = defineClass(name, childBytes, 0, childBytes.length);
            if (resolve) resolveClass(value);
            return value;
        }
    }
    static final class Lease implements ModulePayloadLease {
        final ClassLoader loader;
        boolean valid = true, closed;
        int created;
        ModuleScope scope;
        CompletableFuture<Void> task;
        boolean resident, closeFailure, createFailure;
        Lease() { loader = new Isolated(); }
        @Override public ModuleFeature create(ModuleServices services) throws Exception {
            created++; scope = services.scope();
            check(scope.canRegister() && !scope.allowed(), "factory may register inert callbacks before activation");
            if (createFailure) throw new IllegalStateException("factory failure");
            if (task != null) scope.own(() -> { }, task);
            services.hook("test.value", args -> "child");
            return resident ? () -> "Example" : (ModuleFeature) loader.loadClass("fixture.ChildFeature").getConstructor().newInstance();
        }
        @Override public ClassLoader definingLoader() { return loader; }
        @Override public boolean authorized() { return valid; }
        @Override public void close() { if (closeFailure) throw new IllegalStateException("lease close failed"); closed = true; }
    }
    static final class Harness {
        final Queue<Runnable> queue = new ArrayDeque<>();
        final List<CompletableFuture<ModulePayloadLease>> requests = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        int installed, retired;
        boolean retirementFailure;
        boolean synchronousCleanupFailure;
        final ModulePayloadRuntime runtime = new ModulePayloadRuntime((id, generation) -> {
            if(synchronousCleanupFailure)throw new ModulePayloadProvider.CleanupFailure("synchronous cleanup failed",new IllegalStateException("fixture"));
            CompletableFuture<ModulePayloadLease> response = new CompletableFuture<>();
            requests.add(response); return response;
        }, queue::add, id -> null, new ModulePayloadRuntime.Listener() {
            public void installed(String id, ModuleFeature feature) { installed++; }
            public void retiring(String id, ModuleFeature feature) {
                retired++; if (retirementFailure) throw new IllegalStateException("cleanup failed");
            }
            public void failed(String id, Throwable failure) { failures.add(id); }
        });
        Harness() { runtime.register("example"); runtime.hooks().declare("test.value", 0); }
        void desired(boolean value) { runtime.synchronize(value ? Set.of("example") : Set.of()); }
        void pump() {
            while (!queue.isEmpty()) queue.remove().run();
            if (runtime.ready("example")) runtime.started("example");
        }
        Lease enable() { desired(true); Lease lease = new Lease(); requests.getLast().complete(lease); pump(); return lease; }
        Object hook() throws Exception { return runtime.hooks().invoke("example", "test.value", "vanilla"); }
    }
    public static void main(String[] args) throws Exception {
        childBytes = Files.readAllBytes(Path.of(args[0]));
        {
            Harness h = new Harness(); h.desired(true); check(h.installed == 0, "download must not install synchronously");
            Lease l = new Lease(); h.requests.getFirst().complete(l); check(h.installed == 0, "worker callback must enqueue");
            h.pump(); check(h.installed == 1 && h.runtime.authorized("example"), "isolated feature installs");
            check(h.hook().equals("child"), "authorized hook dispatches");
            h.desired(false); check(h.retired == 1 && !l.closed, "scope drains before artifact retirement");
            check(h.hook().equals("vanilla"), "disabled callback uses vanilla immediately");
            h.pump(); check(l.closed, "retired artifact is closed"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); h.desired(false); h.desired(true);
            check(h.requests.size() == 1, "re-enable waits for canceled generation");
            Lease old = new Lease(); h.requests.getFirst().complete(old); h.pump();
            check(old.closed && old.created == 0 && h.installed == 0, "stale delivery never defines code");
            h.desired(true); check(h.requests.size() == 2, "fresh generation can request after retirement"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); h.desired(false); h.desired(true);
            h.requests.getFirst().completeExceptionally(new ModulePayloadProvider.CleanupFailure("failed canceled-buffer disposal",new IllegalStateException("fixture")));h.pump();
            check(h.runtime.state("example")==ModulePayloadRuntime.State.FAILED&&!h.failures.isEmpty(),"Failed canceled preparation quarantines its slot");
            h.desired(false);h.desired(true);check(h.requests.size()==1,"Cleanup failure cannot overlap a replacement generation");
            h.runtime.close();check(h.runtime.stopped().toCompletableFuture().isCompletedExceptionally(),"Canceled cleanup failure retains an exceptional retirement barrier");cases++;
        }
        {
            Harness h = new Harness();h.desired(true);
            h.requests.getFirst().completeExceptionally(new CompletionException(new ModulePayloadProvider.CleanupFailure("failed live-request disposal",new IllegalStateException("fixture"))));h.pump();
            check(h.runtime.state("example")==ModulePayloadRuntime.State.FAILED&&!h.failures.isEmpty(),"Uncanceled preparation cleanup failure quarantines its slot");
            h.desired(false);h.desired(true);check(h.requests.size()==1,"Uncanceled cleanup failure cannot retry into a second generation");
            h.runtime.close();check(h.runtime.stopped().toCompletableFuture().isCompletedExceptionally(),"Uncanceled cleanup failure preserves exceptional retirement barrier");cases++;
        }
        {
            Harness h = new Harness();h.synchronousCleanupFailure=true;h.desired(true);
            check(h.runtime.state("example")==ModulePayloadRuntime.State.FAILED&&h.failures.size()==1,"Synchronous acquisition cleanup failure quarantines its slot");
            h.synchronousCleanupFailure=false;h.desired(false);h.desired(true);check(h.requests.isEmpty(),"Synchronous cleanup failure cannot retry after toggling");
            h.runtime.close();check(h.runtime.stopped().toCompletableFuture().isCompletedExceptionally(),"Synchronous cleanup failure preserves exceptional retirement barrier");cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); Lease l = new Lease(); l.valid = false;
            h.requests.getFirst().complete(l); h.pump();
            check(l.created == 0 && l.closed && h.failures.size() == 1, "expired capability fails before definition"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); Lease l = new Lease(); l.resident = true;
            h.requests.getFirst().complete(l); h.pump();
            check(h.installed == 0 && l.closed && !h.failures.isEmpty(), "parent implementation fallback rejected"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); Lease l = new Lease(); l.task = new CompletableFuture<>();
            h.requests.getFirst().complete(l); h.pump(); h.desired(false); h.pump();
            check(!l.closed && !h.runtime.authorized("example"), "running task blocks close but not revocation");
            h.desired(true); check(h.requests.size() == 1, "no overlapping classloader generation");
            l.task.complete(null); h.pump(); check(l.closed, "worker drain permits retirement");
            h.desired(true); check(h.requests.size() == 2, "re-enable begins after worker exit"); cases++;
        }
        {
            Harness h = new Harness(); Lease l = h.enable(); l.valid = false;
            check(h.hook().equals("vanilla"), "lease expiry blocks every callback independently");
            h.desired(true); h.pump(); check(l.closed && h.retired == 1, "expired active module retires"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); h.runtime.close();
            CompletableFuture<Void> stopped = h.runtime.stopped().toCompletableFuture();
            check(!stopped.isDone(), "shutdown awaits in-flight delivery cleanup");
            Lease l = new Lease(); h.requests.getFirst().complete(l); h.pump();
            check(stopped.isDone() && l.closed && l.created == 0, "late shutdown delivery discarded"); cases++;
        }
        {
            Harness h = new Harness(); Lease l = h.enable(); h.retirementFailure = true; h.desired(false); h.pump();
            check(!l.closed && !h.runtime.authorized("example"), "failed cleanup quarantines instead of claiming unload");
            h.runtime.close(); check(h.runtime.stopped().toCompletableFuture().isCompletedExceptionally(), "retirement failure observable"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); h.requests.getFirst().completeExceptionally(new IllegalStateException("offline")); h.pump();
            check(h.failures.size() == 1 && h.installed == 0, "delivery failure surfaced");
            h.desired(false); h.desired(true); check(h.requests.size() == 2, "explicit toggle retries failed delivery"); cases++;
        }
        {
            ModuleScope scope = new ModuleScope(); List<Integer> order = new ArrayList<>();
            CompletableFuture<Void> drained = new CompletableFuture<>();
            scope.own(() -> order.add(1)); scope.own(() -> order.add(2), drained); scope.close();
            check(order.equals(List.of(2, 1)), "scope cleanup is LIFO");
            check(!scope.retired().toCompletableFuture().isDone(), "scope does not confuse cancel with termination");
            drained.complete(null); check(scope.retired().toCompletableFuture().isDone(), "scope observes actual completion");
            reject(() -> scope.own(() -> { }), "closed scope accepts no new ownership"); cases++;
        }
        {
            ModuleHookRegistry hooks = new ModuleHookRegistry((id, generation) -> generation == 1);
            hooks.declare("test", 1); reject(() -> hooks.connect("a", 1, "unknown", args2 -> null), "unknown operation");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var registration = hooks.connect("a", 1, "test", args2 -> { entered.countDown(); release.await(); return args2[0]; });
            reject(() -> hooks.connect("a", 1, "test", args2 -> null), "duplicate hook");
            CompletableFuture<Object> call = CompletableFuture.supplyAsync(() -> {
                try { return hooks.invoke("a", "test", null, "result"); }
                catch (Exception e) { throw new CompletionException(e); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "callback entered"); registration.close();
            check(!registration.detached().toCompletableFuture().isDone(), "executing callback must drain");
            check(hooks.invoke("a", "test", "fallback", "x").equals("fallback"), "retirement blocks new invocation");
            release.countDown(); check(call.get(5, TimeUnit.SECONDS).equals("result"), "executing call finishes normally");
            check(registration.detached().toCompletableFuture().isDone(), "detachment finishes after call");
            var stale = hooks.connect("a", 2, "test", args2 -> "bad");
            check(hooks.invoke("a", "test", "fallback", "x").equals("fallback"), "generation rejected");
            stale.close(); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); Lease l = new Lease(); l.createFailure = true;
            h.requests.getFirst().complete(l); h.pump(); h.desired(true);
            check(h.requests.size() == 1 && l.closed, "failed initialization cannot repeatedly fetch with saved enabled intent");
            h.desired(false); h.desired(true); check(h.requests.size() == 2, "off/on retries failed factory"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); h.runtime.close(); Lease l = new Lease(); l.closeFailure = true;
            h.requests.getFirst().complete(l); h.pump();
            check(h.runtime.stopped().toCompletableFuture().isCompletedExceptionally(), "uninstalled cleanup failure is observable"); cases++;
        }
        {
            ModuleScope outer = new ModuleScope(), inner = new ModuleScope();
            check(ModuleScope.current() == null, "no ambient module owner");
            try (var a = outer.enter()) {
                check(ModuleScope.current() == outer, "ownership context enters");
                try (var b = inner.enter()) { check(ModuleScope.current() == inner, "nested module owner enters"); }
                check(ModuleScope.current() == outer, "nested owner restores");
                check(CompletableFuture.supplyAsync(ModuleScope::current).get(5, TimeUnit.SECONDS) == null,
                    "module ownership is never inherited by unrelated worker tasks");
            }
            check(ModuleScope.current() == null, "outer owner restores"); cases++;
        }
        {
            Map<String,Object> values = new HashMap<>(); Object baseline = new Object(), a = new Object(), b = new Object();
            values.put("pipeline", baseline); ScopedRegistry<String,Object> registry = new ScopedRegistry<>(values);
            var first = registry.put("pipeline", a); var second = registry.put("pipeline", b);
            first.close(); check(values.get("pipeline") == b, "retiring earlier pipeline cannot remove a later owner");
            second.close(); check(values.get("pipeline") == baseline, "retiring later owner never revives removed module");
            first = registry.put("pipeline", a); second = registry.put("pipeline", b);
            second.close(); check(values.get("pipeline") == a, "reverse retirement restores still-live owner");
            first.close(); check(values.get("pipeline") == baseline, "final retirement restores vanilla baseline");
            Object external = new Object(); first = registry.put("pipeline", a); values.put("pipeline", external);
            second = registry.put("pipeline", b); first.close(); second.close();
            check(values.get("pipeline") == external, "external replacement survives old scope retirement"); cases++;
        }
        {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
            worker.submit(() -> { entered.countDown(); boolean done = false;
                while (!done) try { finish.await(); done = true; } catch (InterruptedException expected) { }
            });
            check(entered.await(5, TimeUnit.SECONDS), "scanner worker started");
            var stopped = ModuleRetirement.stopWorker(worker).toCompletableFuture();
            check(!stopped.isDone(), "interruption request is not worker termination");
            finish.countDown(); stopped.get(5, TimeUnit.SECONDS);
            check(worker.isTerminated(), "worker retirement observes executor termination"); cases++;
        }
        {
            Harness h = new Harness(); h.desired(true); Lease l = new Lease(); h.requests.getFirst().complete(l);
            while (!h.queue.isEmpty()) h.queue.remove().run();
            try (var entering = h.runtime.enter("example")) {
                check(l.scope.canRegister() && !l.scope.allowed(), "onEnable may register without activating callbacks");
            }
            check(!l.scope.canRegister(), "registration requires explicit owning lifecycle context");
            check(h.runtime.ready("example") && !h.runtime.authorized("example"), "loaded feature does not dispatch before onEnable");
            check(h.hook().equals("vanilla"), "pre-onEnable callback uses host fallback");
            h.runtime.started("example"); check(h.hook().equals("child"), "successful onEnable authorizes callbacks");
            h.runtime.paused("example"); check(h.hook().equals("vanilla"), "paused world lifecycle blocks callbacks"); cases++;
        }

        {
            ModuleScope scope = new ModuleScope();
            CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
            List<String> released = new CopyOnWriteArrayList<>();
            scope.releaseAfterCallbacks(() -> released.add("gpu"));
            var call = CompletableFuture.runAsync(() -> {
                try (var context = scope.tryEnter()) {
                    if (context == null) throw new AssertionError("scope should enter");
                    entered.countDown(); done.await();
                } catch (InterruptedException e) { throw new CompletionException(e); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "scope callback entered"); scope.close();
            check(released.isEmpty() && !scope.retired().toCompletableFuture().isDone(), "entered context itself blocks GPU retirement");
            check(scope.tryEnter() == null, "retiring scope refuses new callbacks");
            done.countDown(); call.get(5, TimeUnit.SECONDS);
            scope.retired().toCompletableFuture().get(5, TimeUnit.SECONDS);
            check(released.equals(List.of("gpu")), "GPU release happens after executing context exits"); cases++;
        }
        {
            ModuleScope scope = new ModuleScope(); List<Runnable> queued = new ArrayList<>(); int[] ran = {0};
            var operation = scope.execute(queued::add, () -> ran[0]++);
            scope.close(); check(!scope.retired().toCompletableFuture().isDone(), "queued callback must drain rather than fake cancellation");
            queued.removeFirst().run();
            check(ran[0] == 0 && operation.toCompletableFuture().isDone(), "queued callback skips child code after revoke");
            check(scope.retired().toCompletableFuture().isDone(), "queue drain permits retirement"); cases++;
        }
        {
            ModuleScope scope = new ModuleScope(); int[] closed = {0};
            for (int i=0;i<10000;i++) scope.ownTransient(() -> closed[0]++, CompletableFuture.completedFuture(null));
            scope.close(); check(closed[0] == 0, "completed transient operations do not accumulate retirement handles"); cases++;
        }
        {
            ModuleScope scope = new ModuleScope(); Object renderState = new Object(); ModuleFrame<Object> frame;
            try (var context = scope.enter()) { frame=ModuleFrame.create(); frame.set(renderState,new Object()); }
            check(frame.get(renderState)!=null, "frame has data during active scope"); scope.close();
            frame.set(renderState,new Object()); check(frame.get(renderState)==null, "retired frame clears and cannot republish"); cases++;
        }
        {
            Object event = new Object(); List<Tick> proxies = new ArrayList<>(); int[] calls={0,0};
            ModuleScope first=new ModuleScope("first",()->true,Runnable::run), second=new ModuleScope("second",()->true,Runnable::run);
            try(var context=first.enter()) { dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.register(event,"same",Tick.class,()->{
                check(ModuleScope.current()==first,"event callback establishes its scope"); calls[0]++;
            },proxies::add); }
            try(var context=second.enter()) { dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.register(event,"same",Tick.class,()->calls[1]++,proxies::add); }
            check(proxies.size()==2,"different modules with same helper event name each register once");
            proxies.forEach(Tick::fire); check(Arrays.equals(calls,new int[]{1,1}),"both module callbacks are active");
            first.close(); proxies.forEach(Tick::fire); check(Arrays.equals(calls,new int[]{1,2}),"one module retirement preserves other module callback");
            ModuleScope replacement=new ModuleScope("first",()->true,Runnable::run);
            try(var context=replacement.enter()) { dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.register(event,"same",Tick.class,()->calls[0]++,proxies::add); }
            check(proxies.size()==2,"reenable reuses existing resident event proxy");
            check(proxies.getFirst().identity()==7,"game listener interface defaults survive detachable bridge");
            proxies.forEach(Tick::fire);check(Arrays.equals(calls,new int[]{2,3}),"replacement callback activates without duplicates");
            second.close();replacement.close();check(ModuleScope.current()==null,"event callback scope is restored"); cases++;
        }
        {
            Harness h = new Harness(); Lease l=h.enable(); l.valid=false;
            try (var context=h.runtime.enter("example")) {
                check(!l.scope.canRegister(), "lease expiry blocks new inert registrations too");
            }
            h.desired(false); h.pump(); cases++;
        }
        {
            ModuleScope scope=new ModuleScope("timeout",()->true,()->true,Runnable::run,25);
            CompletableFuture<Void> stuck=new CompletableFuture<>();int[] released={0};
            scope.own(()->{},stuck);scope.releaseAfterCallbacks(()->released[0]++);scope.close();
            try { scope.retired().toCompletableFuture().get(3,TimeUnit.SECONDS);throw new AssertionError("stuck drain reported success"); }
            catch(ExecutionException expected) { check(expected.getCause() instanceof TimeoutException,"bounded drain deadline reports timeout"); }
            check(released[0]==0,"deadline failure keeps GPU/native resources quarantined");
            stuck.complete(null);check(released[0]==0,"late worker completion cannot turn failed quarantine into successful disposal");cases++;
        }
        {
            List<Runnable> owner=new ArrayList<>();ModuleScope scope=new ModuleScope("owner_timeout",()->true,()->true,owner::add,25);
            int[] released={0};scope.releaseAfterCallbacks(()->released[0]++);scope.close();
            try { scope.retired().toCompletableFuture().get(3,TimeUnit.SECONDS);throw new AssertionError("stuck owner reported success"); }
            catch(ExecutionException expected) { check(expected.getCause() instanceof TimeoutException,"owner cleanup queue has same bounded deadline"); }
            owner.forEach(Runnable::run);check(released[0]==0,"late owner callback respects quarantine");cases++;
        }
        System.out.println("{\"status\":\"passed\",\"cases\":" + cases + ",\"assertions\":" + assertions + "}");
    }
}

