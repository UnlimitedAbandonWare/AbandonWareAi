package infra.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SingleFlightCacheElectionContractTest {

    @Test
    void overlappingCallersElectExactlyOneLoaderForTheSameKey() throws Exception {
        SingleFlightCache<String, String> cache = new SingleFlightCache<>();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch followerCallStarted = new CountDownLatch(1);
        AtomicReference<Thread> followerThread = new AtomicReference<>();

        Future<String> owner = callers.submit(() -> cache.getOrCompute("shared-key", () -> {
            loaderCalls.incrementAndGet();
            ownerEntered.countDown();
            assertTrue(releaseOwner.await(5, TimeUnit.SECONDS), "owner loader release timed out");
            return "owner-value";
        }));

        try {
            assertTrue(ownerEntered.await(2, TimeUnit.SECONDS), "owner loader did not start");
            Future<String> follower = callers.submit(() -> {
                followerThread.set(Thread.currentThread());
                followerCallStarted.countDown();
                return cache.getOrCompute("shared-key", () -> {
                    loaderCalls.incrementAndGet();
                    return "follower-value";
                });
            });

            assertTrue(followerCallStarted.await(2, TimeUnit.SECONDS), "follower caller did not start");
            awaitFollowerLoaderDecision(follower, followerThread, loaderCalls);

            assertEquals(1, loaderCalls.get(), "only the elected caller may execute the loader");
            assertFalse(follower.isDone(), "follower must await the elected caller's future");

            releaseOwner.countDown();
            assertEquals("owner-value", owner.get(2, TimeUnit.SECONDS));
            assertEquals("owner-value", follower.get(2, TimeUnit.SECONDS));
            assertEquals(1, loaderCalls.get());
        } finally {
            releaseOwner.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void awaitFollowerLoaderDecision(
            Future<String> follower,
            AtomicReference<Thread> followerThread,
            AtomicInteger loaderCalls) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Thread thread = followerThread.get();
            if (loaderCalls.get() > 1
                    || follower.isDone()
                    || (thread != null && thread.getState() == Thread.State.WAITING)) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("follower did not reach a loader-or-wait decision");
    }
}
