package com.example.lms.assist;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Receives a bounded response body under one monotonic Java 17 transport deadline. */
final class BoundedHttpBody {
    private BoundedHttpBody() {}

    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request,
                                    int limit, long deadlineNanos)
            throws IOException, InterruptedException {
        if (limit < 1) throw new IllegalArgumentException("response_limit_invalid");
        if (deadlineNanos - System.nanoTime() <= 0)
            throw new HttpTimeoutException("response_deadline_exceeded");
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<LimitedSubscriber> active = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request, info -> {
            LimitedSubscriber subscriber = new LimitedSubscriber(limit, info.statusCode());
            active.set(subscriber);
            if (aborted.get()) subscriber.abort(new IOException("body_cancelled"));
            return subscriber;
        });
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return exchange.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            throw new HttpTimeoutException("response_deadline_exceeded");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("response_transport_failed");
        } catch (CancellationException cancelled) {
            throw new IOException("response_transport_cancelled");
        } finally {
            // Also handles cancellation before the response BodyHandler runs.
            aborted.set(true);
            LimitedSubscriber subscriber = active.get();
            if (subscriber != null && !subscriber.result.isDone())
                subscriber.abort(new IOException("body_cancelled"));
            if (!exchange.isDone()) exchange.cancel(true);
        }
    }

    static final class TooLarge extends IOException {
        private final int httpStatus;
        TooLarge(int httpStatus) {
            super("oversized_response");
            this.httpStatus = httpStatus;
        }
        int httpStatus() { return httpStatus; }
    }

    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final int httpStatus;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        LimitedSubscriber(int limit, int httpStatus) { this.limit = limit; this.httpStatus = httpStatus; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) {
            if (!subscription.compareAndSet(null, next) || result.isDone()) next.cancel();
            else next.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (result.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if ((long) bytes.size() + chunk.remaining() > limit) {
                    abort(new TooLarge(httpStatus));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.write(part, 0, part.length);
            }
            Flow.Subscription current = subscription.get();
            if (current != null && !result.isDone()) current.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        void abort(IOException error) {
            result.completeExceptionally(error);
            Flow.Subscription current = subscription.get();
            if (current != null) current.cancel();
        }
    }
}
