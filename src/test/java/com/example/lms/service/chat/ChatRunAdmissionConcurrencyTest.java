package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatRunAdmissionConcurrencyTest {
    @Test void admittedWorkMayFinishLateButStopRejectsNewWorkAndDoesNotLockOtherRuns() throws Exception {
        var eviction = new ScheduledThreadPoolExecutor(1);
        var workers = Executors.newFixedThreadPool(2);
        var registry = new ChatRunRegistry(eviction);
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        var a = registry.beginOrJoin(501L).context();
        var b = registry.beginOrJoin(502L).context();
        CountDownLatch admitted = new CountDownLatch(1), delegate = new CountDownLatch(1);
        CountDownLatch disposing = new CountDownLatch(1), releaseDisposal = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        a.registerCancellationHandle(() -> {
            disposing.countDown();
            try { assertTrue(releaseDisposal.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("synthetic cleanup exception");
        });
        try {
            Future<?> old = workers.submit(() -> {
                assertTrue(a.admitCall(() -> {}));
                admitted.countDown();
                try { assertTrue(delegate.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                calls.incrementAndGet(); // Already admitted work; not a new post-Stop attempt.
            });
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            Future<Boolean> stop = workers.submit(() -> registry.cancelExact(501L, a.clientToken()));
            assertTrue(disposing.await(5, TimeUnit.SECONDS));
            assertFalse(a.admitCall(calls::incrementAndGet));
            assertTrue(b.admitCall(calls::incrementAndGet), "unrelated admission completes while A disposal is held");
            delegate.countDown();
            old.get(5, TimeUnit.SECONDS);
            releaseDisposal.countDown();
            assertTrue(stop.get(5, TimeUnit.SECONDS), "cleanup exception must not undo cancellation");
            assertEquals(2, calls.get());
            assertFalse(a.permitsEmission());
            assertTrue(b.permitsEmission());
            assertNotEquals(a.clientToken(), registry.beginOrJoin(501L).context().clientToken());
        } finally {
            delegate.countDown(); releaseDisposal.countDown();
            workers.shutdownNow(); eviction.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(eviction.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
