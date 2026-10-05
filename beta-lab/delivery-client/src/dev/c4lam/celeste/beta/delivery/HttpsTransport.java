package dev.c4lam.celeste.beta.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import dev.c4lam.celeste.auth.PinnedTls;

/** Default transport bounds each body while it streams and cancels on size/deadline failure. */
final class HttpsTransport implements BetaDeliveryClient.Transport, ModuleDeliveryClient.Transport {
    private final URI origin;
    private final byte[] serverKey;
    private PinnedTls.Owner owner;
    private boolean closed;
    private final java.util.Set<CompletableFuture<?>> requests = new java.util.HashSet<>();
    HttpsTransport(URI origin, byte[] serverKey) { this.origin = origin; this.serverKey = serverKey.clone(); }

    private synchronized PinnedTls.Owner owner() throws IOException {
        if (closed) throw new IOException("Delivery transport is closed");
        if (owner == null) owner = PinnedTls.acquire(origin, serverKey, "celeste_plus");
        return owner;
    }

    @Override public void close() {
        PinnedTls.Owner releasing;
        List<CompletableFuture<?>> cancelling;
        synchronized (this) {
            if (closed) return;
            closed = true; releasing = owner; owner = null;
            cancelling = List.copyOf(requests); requests.clear();
        }
        cancelling.forEach(future -> future.cancel(true));
        if (releasing != null) releasing.close();
    }

    @Override public BetaDeliveryClient.Reply get(URI uri, Map<String, String> headers, int maximumBytes, Duration timeout)
        throws IOException, InterruptedException {
        return request("GET", uri, headers, new byte[0], maximumBytes, timeout);
    }

    @Override public BetaDeliveryClient.Reply request(String method, URI uri, Map<String, String> headers, byte[] content,
                                    int maximumBytes, Duration timeout) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !origin.getHost().equalsIgnoreCase(uri.getHost())
                || origin.getPort() != uri.getPort() || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IOException("Pinned HTTPS origin required");
        if (!method.equals("GET") && !method.equals("POST")) throw new IOException("Unsupported delivery method");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
            .method(method, content.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content));
        headers.forEach(builder::header);
        AtomicReference<BoundedBody> subscriber = new AtomicReference<>();
        HttpClient http = owner().client();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(builder.build(), info -> {
            BoundedBody body = new BoundedBody(maximumBytes);
            subscriber.set(body);
            return body;
        });
        try {
            synchronized (this) {
                if (closed) { future.cancel(true); throw new IOException("Delivery transport is closed"); }
                requests.add(future);
            }
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (this) {
                if (closed) { Arrays.fill(response.body(), (byte) 0); throw new IOException("Delivery transport is closed"); }
            }
            return new BetaDeliveryClient.Reply(response.uri(), response.statusCode(), response.headers().map(), response.body());
        } catch (ExecutionException exception) {
            throw new IOException("Beta HTTPS transfer failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException("Beta HTTPS transfer deadline exceeded", exception);
        } finally {
            synchronized (this) { requests.remove(future); }
            if (!future.isDone() || future.isCompletedExceptionally()) {
                BoundedBody body = subscriber.get();
                if (body != null) body.fail(new IOException("Beta transfer cancelled"));
                future.cancel(true);
            }
        }
    }

    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private byte[] bytes;
        private int used;
        private Flow.Subscription subscription;
        BoundedBody(int maximumBytes) {
            if (maximumBytes <= 0 || maximumBytes > BetaDeliveryClient.MAX_ARTIFACT_BYTES + ArtifactEnvelope.OVERHEAD) throw new IllegalArgumentException("Invalid body limit");
            bytes = new byte[maximumBytes];
        }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if (subscription != null || result.isDone()) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> blocks) {
            if (result.isDone()) return;
            for (ByteBuffer block : blocks) {
                int length = block.remaining();
                if (length > bytes.length - used) { fail(new IOException("Beta response exceeded its byte limit")); return; }
                block.get(bytes, used, length); used += length;
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { fail(error); }
        @Override public synchronized void onComplete() {
            if (result.isDone()) return;
            byte[] completed = used == bytes.length ? bytes : Arrays.copyOf(bytes, used);
            if (completed != bytes) Arrays.fill(bytes, (byte) 0);
            bytes = null;
            result.complete(completed);
        }
        synchronized void fail(Throwable error) {
            if (result.isDone()) return;
            if (subscription != null) subscription.cancel();
            Arrays.fill(bytes, (byte) 0); bytes = null;
            result.completeExceptionally(error);
        }
    }
}
