import dev.c4lam.celeste.module.delivery.ModuleResourceAssets;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ModuleResourceAssetsCheck {
    static int assertions;
    static final String A = "assets/celeste/shaders/core/a.fsh";
    static final String B = "assets/celeste/textures/b.png";
    static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("assertion " + assertions); }
    static void rejected(Runnable action) { boolean rejected = false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; } check(rejected); }
    static boolean zeros(byte[] bytes) { for (byte b : bytes) if (b != 0) return false; return true; }
    static ModuleScope scope() { return new ModuleScope("test", () -> true, Runnable::run); }
    public static void main(String[] args) throws Exception {
        java.util.Set<String> retiredPaths = new java.util.HashSet<>();
        java.util.Set<String> availablePaths = new java.util.HashSet<>();
        ModuleResourceAssets.setInstallationObserver(availablePaths::addAll);
        ModuleResourceAssets.setRetirementObserver(retiredPaths::addAll);
        @SuppressWarnings("unchecked") var childObserver = (java.util.function.Consumer<java.util.Set<String>>) java.lang.reflect.Proxy.newProxyInstance(new ClassLoader() {}, new Class[]{java.util.function.Consumer.class}, (p,m,a)->null);
        rejected(() -> ModuleResourceAssets.setRetirementObserver(childObserver));
        ModuleScope first = new ModuleScope(() -> false);
        byte[] original = {1,2,3};
        ModuleResourceAssets.install(first, Map.of(A, original));
        check(Arrays.equals(original, ModuleResourceAssets.bytes(first,A))); // Static initialization before enabled.
        byte[] copy = ModuleResourceAssets.bytes(first,A); copy[0] = 9;
        check(ModuleResourceAssets.bytes(first,A)[0] == 1);
        var stream = ModuleResourceAssets.open(A);
        check(ModuleResourceAssets.paths().equals(java.util.List.of(A)));
        ModuleScope outsider = scope();
        check(ModuleResourceAssets.bytes(outsider,A) == null);
        byte[] equal = {1,2,3}; ModuleResourceAssets.install(outsider, Map.of(A,equal));
        check(ModuleResourceAssets.retainedBytes() == 6);
        ModuleScope conflict = scope(); byte[] bad = {8};
        rejected(() -> ModuleResourceAssets.install(conflict,Map.of(A,bad,B,new byte[]{7})));
        check(ModuleResourceAssets.bytes(conflict,B) == null && bad[0] == 8);
        rejected(() -> ModuleResourceAssets.install(first,Map.of(B,new byte[]{7})));
        first.close(); first.retired().toCompletableFuture().join();
        check(zeros(original) && equal[0] == 1);
        boolean revoked = false;
        try { stream.read(); } catch (java.io.IOException expected) { revoked = true; }
        check(revoked); stream.close();
        check(retiredPaths.isEmpty()); // Equal-byte surviving owner is still published.
        check(ModuleResourceAssets.bytes(null,A)[0] == 1);
        outsider.close(); outsider.retired().toCompletableFuture().join();
        check(ModuleResourceAssets.paths().isEmpty() && ModuleResourceAssets.retainedBytes() == 0);
        check(retiredPaths.contains(A) && availablePaths.contains(A));

        ModuleScope pending = scope(); byte[] retained = {4};
        CompletableFuture<Void> work = new CompletableFuture<>();
        ModuleResourceAssets.install(pending,Map.of(A,retained));
        pending.own(() -> {},work);
        pending.releaseAfterCallbacks(() -> check(retained[0] == 4)); // GPU release precedes asset wipe.
        pending.close(); check(retained[0] == 4 && !pending.retired().toCompletableFuture().isDone());
        check(ModuleResourceAssets.bytes(pending,A) == null);
        work.complete(null); pending.retired().toCompletableFuture().join(); check(zeros(retained));
        rejected(() -> ModuleResourceAssets.install(pending,Map.of()));
        ModuleScope invalid = scope();
        for(String path : new String[]{"assets/celeste/../bad", "assets/minecraft/x", "x.class", "assets/celeste/X", "assets/celeste/a//b"})
            rejected(() -> ModuleResourceAssets.install(invalid,Map.of(path,new byte[1])));
        rejected(() -> ModuleResourceAssets.install(invalid,Map.of(A,new byte[8*1024*1024+1])));
        ModuleResourceAssets.install(invalid,Map.of()); invalid.close(); invalid.retired().toCompletableFuture().join();
        byte[] host = {5}; Object owner = new Object();
        AutoCloseable fixed = ModuleResourceAssets.registerResident(owner,Map.of(A,host));
        ModuleScope third = scope(); check(ModuleResourceAssets.bytes(third,A)[0] == 5);
        rejected(() -> ModuleResourceAssets.install(third,Map.of(A,new byte[]{6})));
        byte[] shared = {5}; ModuleResourceAssets.install(third,Map.of(A,shared));
        third.close(); third.retired().toCompletableFuture().join();
        check(zeros(shared) && host[0] == 5); fixed.close();
        check(host[0] == 5 && ModuleResourceAssets.retainedBytes() == 0);
        System.out.println("ModuleResourceAssetsCheck: 12 cases, " + assertions + " assertions passed");
    }
}
