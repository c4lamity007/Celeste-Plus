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

/** Default transport bounds each body while it streams and cancels on size/deadline failure. */
final class HttpsTransport implements BetaDeliveryClient.Transport {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override public BetaDeliveryClient.Reply get(URI uri, Map<String, String> headers, int maximumBytes, Duration timeout)
        throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("HTTPS required");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach(builder::header);
        AtomicReference<BoundedBody> subscriber = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(builder.build(), info -> {
            BoundedBody body = new BoundedBody(maximumBytes);
            subscriber.set(body);
            return body;
        });
        try {
            HttpResponse<byte[]> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new BetaDeliveryClient.Reply(response.uri(), response.statusCode(), response.headers().map(), response.body());
        } catch (ExecutionException exception) {
            throw new IOException("Beta HTTPS transfer failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException("Beta HTTPS transfer deadline exceeded", exception);
        } finally {
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
            if (maximumBytes <= 0 || maximumBytes > BetaDeliveryClient.MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("Invalid body limit");
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
