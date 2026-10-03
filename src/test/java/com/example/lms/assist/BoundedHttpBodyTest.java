package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpBodyTest {
    static final class Subscription implements Flow.Subscription {
        final AtomicInteger cancels = new AtomicInteger(), requests = new AtomicInteger();
        public void request(long n) { requests.incrementAndGet(); }
        public void cancel() { cancels.incrementAndGet(); }
    }
    @Test void cancelBeforeOnSubscribeCancelsLaterSubscription() {
        var subscriber = new BoundedHttpBody.LimitedSubscriber(8, 200);
        subscriber.abort(new IOException("fixture_timeout"));
        var subscription = new Subscription(); subscriber.onSubscribe(subscription);
        assertEquals(1, subscription.cancels.get()); assertEquals(0, subscription.requests.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void fragmentedLimitAndMultiBufferBatchCancelOnOverflow() {
        var subscriber = new BoundedHttpBody.LimitedSubscriber(4, 403);
        var subscription = new Subscription(); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1]), ByteBuffer.wrap(new byte[2])));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        var oversized = assertInstanceOf(BoundedHttpBody.TooLarge.class, failure.getCause());
        assertEquals(403, oversized.httpStatus()); assertEquals(1, subscription.cancels.get());
    }
    @Test void exactBoundaryAndOneByteBelowAreAccepted() {
        for (int size : new int[]{3, 4}) {
            var subscriber = new BoundedHttpBody.LimitedSubscriber(4, 200);
            var subscription = new Subscription(); subscriber.onSubscribe(subscription);
            subscriber.onNext(List.of(ByteBuffer.wrap(new byte[size]))); subscriber.onComplete();
            assertEquals(size, subscriber.getBody().toCompletableFuture().join().length);
            assertEquals(0, subscription.cancels.get());
        }
    }
}
