package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Real JDK HTTP selector lifetime with disposable isolated classes; never sends a request. */
public final class PinnedTlsLifetimeCheck {
    private PinnedTlsLifetimeCheck() { }
    private static int checks;
    private record Probe(WeakReference<ClassLoader> loader, WeakReference<HttpClient> client) { }

    public static void main(String[] args) throws Exception {
        for (int cycle = 0; cycle < 3; cycle++) {
            Probe probe = ownedClient(Path.of(args[0]));
            collect();
            check(probe.loader.get() == null && probe.client.get() == null,
                "retired HTTP client and defining loader are collectible");
        }
        System.out.println("PASS: scoped TLS transport lifetime (" + checks + " checks; real JDK selectors, no requests)");
    }

    private static Probe ownedClient(Path classes) throws Exception {
        URL gson = JsonObject.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL(), gson}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(PinnedTls.class.getName(), true, loader);
            var acquire = type.getMethod("acquire", URI.class, byte[].class, String.class);
            Object first = acquire.invoke(null, URI.create("https://fixture.invalid"), new byte[32], "celeste_plus");
            Object second = acquire.invoke(null, URI.create("https://fixture.invalid"), new byte[32], "celeste_plus");
            var ownerType = first.getClass();
            var getClient = ownerType.getMethod("client");
            var close = ownerType.getMethod("close");
            Map<?, ?> clients = (Map<?, ?>) field(type, "CLIENTS").get(null);
            check(clients.size() == 1, "same trust context shares one entry");
            Object entry = field(ownerType, "entry").get(first);
            HttpClient http = newClient(loader);
            Class<?> cached = Class.forName(PinnedTls.class.getName() + "$Client", true, loader);
            var cacheConstructor = cached.getDeclaredConstructor(HttpClient.class, long.class);
            cacheConstructor.setAccessible(true);
            field(entry.getClass(), "current").set(entry,
                cacheConstructor.newInstance(http, System.nanoTime() + Duration.ofSeconds(120).toNanos()));
            @SuppressWarnings("unchecked") Set<HttpClient> transports = (Set<HttpClient>) field(entry.getClass(), "transports").get(entry);
            transports.add(http);
            check(getClient.invoke(first) == http && getClient.invoke(second) == http, "live owners reuse checked pinned transport");
            close.invoke(first); close.invoke(first);
            check(clients.size() == 1 && !http.isTerminated() && getClient.invoke(second) == http,
                "first and duplicate closes preserve another live owner");
            try { getClient.invoke(first); throw new AssertionError("closed owner returned a client"); }
            catch (java.lang.reflect.InvocationTargetException expected) {
                check(expected.getCause() instanceof java.io.IOException, "closed owner fails before a policy request");
            }
            CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread blocked = new Thread(() -> { synchronized (entry) {
                held.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            } }, "synthetic-pin-refresh");
            HttpClient bootstrap = newClient(loader);
            CompletableFuture<Void> pending = new CompletableFuture<>();
            field(entry.getClass(), "bootstrap").set(entry, bootstrap);
            field(entry.getClass(), "pending").set(entry, pending);
            blocked.start();
            check(held.await(3, TimeUnit.SECONDS), "synthetic refresh is active");
            long started = System.nanoTime();
            CompletableFuture<Void> retirement = CompletableFuture.runAsync(() -> {
                try { close.invoke(second); }
                catch (ReflectiveOperationException error) { throw new java.util.concurrent.CompletionException(error); }
            });
            try { retirement.get(1, TimeUnit.SECONDS); }
            finally { release.countDown(); blocked.join(3000); }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(elapsed < 1000 && clients.isEmpty() && pending.isCancelled(), "last close cancels refresh without waiting for its monitor");
            check(http.awaitTermination(Duration.ofSeconds(3)) && bootstrap.awaitTermination(Duration.ofSeconds(3)),
                "last owner stops pinned and bootstrap selectors");
            Object next = acquire.invoke(null, URI.create("https://fixture.invalid"), new byte[32], "celeste_plus");
            close.invoke(second);
            check(clients.size() == 1 && field(ownerType, "entry").get(next) != entry,
                "stale owner cannot retire a new transport generation");
            close.invoke(next);
            check(clients.isEmpty(), "unused next generation retires cleanly");
            return new Probe(new WeakReference<>(loader), new WeakReference<>(http));
        }
    }

    private static HttpClient newClient(ClassLoader loader) throws Exception {
        Class<?> trustType = Class.forName(PinnedTls.class.getName() + "$LeafTrust", true, loader);
        var constructor = trustType.getDeclaredConstructor(X509ExtendedTrustManager.class, Set.class, long.class);
        constructor.setAccessible(true);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        var delegate = (X509ExtendedTrustManager) factory.getTrustManagers()[0];
        TrustManager trust = (TrustManager) constructor.newInstance(delegate, Set.of("a".repeat(64)),
            System.nanoTime() + Duration.ofSeconds(120).toNanos());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    private static void collect() throws InterruptedException {
        // Fixture only: production lifecycle never forces garbage collection.
        for (int index = 0; index < 12; index++) { System.gc(); Thread.sleep(50); }
    }
}
