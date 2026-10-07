package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ChatRunRegistryTerminalIsolationTest {
    @Test void requestReceiptRecoversOnlyOriginalOwnedRunAndNeverInfersAbsence() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        String owner = "a".repeat(64), key = "b".repeat(64);
        assertTrue(java.util.Arrays.stream(ChatRunRegistry.class.getMethods())
                .anyMatch(method -> method.getName().equals("bindRequestReceipt")),
                "pre-token recovery requires an owner and request binding, not the current session token");
        try {
            var first = registry.beginOrJoin(992L).context();
            assertEquals(true, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "bindRequestReceipt", first, owner, key));
            assertEquals(false, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "bindRequestReceipt", first, owner, "c".repeat(64)), "receipt is immutable");
            assertEquals(java.util.Optional.empty(), org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "tokenForRequestReceipt", 992L, "d".repeat(64), key));
            assertEquals(java.util.Optional.empty(), org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "tokenForRequestReceipt", 992L, owner, "e".repeat(64)));
            registry.markDone(first);
            var second = registry.beginOrJoin(992L).context();
            assertEquals(java.util.Optional.of(first.clientToken()), org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "tokenForRequestReceipt", 992L, owner, key), "R1 lookup cannot return R2");
            assertEquals(java.util.Optional.empty(), org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    registry, "tokenForRequestReceipt", 993L, owner, key), "not registered remains unknown");
            registry.cancelExact(992L, second.clientToken());
        } finally { registry.shutdown(); }
    }

    @Test void unavailableOwnerRenewalRetainsWorkerWhileAuthorityStaysFenced() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        var run = registry.beginOrJoin(991L).context();
        var stopped = new AtomicInteger();
        run.registerCancellationHandle(stopped::incrementAndGet);
        var cluster = org.mockito.Mockito.mock(ChatRunCluster.class);
        var directory = org.mockito.Mockito.mock(ChatRunOwnerDirectory.class);
        org.mockito.Mockito.when(cluster.directory()).thenReturn(directory);
        org.mockito.Mockito.when(directory.renew(org.mockito.ArgumentMatchers.anySet()))
                .thenThrow(new IllegalStateException("synthetic_owner_store_unavailable"));
        registry.setCluster(cluster);
        Object ownedRun = ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
                .getField(registry, "runs")).get(991L);
        org.springframework.test.util.ReflectionTestUtils.setField(ownedRun, "ownerLeaseDeadlineNanos", 0L);
        try {
            registry.renewOwnerLeases();
            assertEquals(0, stopped.get());
            assertFalse(run.isCancellationRequested());
            assertFalse(run.admitCall(() -> fail("unverified owner cannot start another call")));
            assertFalse(run.tryBeginTranscriptCommit());
        } finally { registry.shutdown(); }
    }

    @Test void describeAndStopDoNotWaitForDurableWrite() throws Exception {
        try (Harness h = new Harness()) {
            ChatRunExecutionContext run = h.blockedWrite();
            assertEquals(ChatRunRegistry.Status.COMMITTING,
                    h.pool.submit(() -> h.registry.describeExact(1L, run.clientToken()).orElseThrow().status())
                            .get(500, TimeUnit.MILLISECONDS));
            assertFalse(h.pool.submit(() -> h.registry.cancelExact(1L, run.clientToken()))
                    .get(500, TimeUnit.MILLISECONDS));
            assertFalse(h.registry.markDone(run), "an active write must retain the exact run");
        }
    }

    @Test void staleSweepRetainsOtherLivingRunsAndDoesNotEvictActiveWrite() throws Exception {
        try (Harness h = new Harness()) {
            ChatRunExecutionContext first = h.blockedWrite();
            ChatRunExecutionContext second = h.registry.beginOrJoin(2L).context();
            h.clock.set(31_000);
            h.pool.submit(h.registry::runStaleSweepNow).get(500, TimeUnit.MILLISECONDS);
            assertEquals(ChatRunRegistry.Status.RUNNING,
                    h.registry.describeExact(2L, second.clientToken()).orElseThrow().status());
            assertEquals(ChatRunRegistry.Status.COMMITTING,
                    h.registry.describeExact(1L, first.clientToken()).orElseThrow().status());
            assertFalse(h.registry.beginOrJoin(1L).owner());
        }
    }

    @Test void deletionDeadlineRetainsDrainingRunAndBlocksLateWrites() throws Exception {
        try (Harness h = new Harness()) {
            ChatRunExecutionContext first = h.blockedWrite();
            AtomicInteger physicalDeletes = new AtomicInteger();
            Future<String> deletion = h.pool.submit(() -> {
                try {
                    h.registry.cancelSessionForDeletion(1L);
                    physicalDeletes.incrementAndGet();
                    return "deleted";
                } catch (ChatRunRegistry.SessionDeletionFenceException fence) { return fence.reason(); }
            });
            assertEquals(ChatRunRegistry.SESSION_DELETION_CANCEL_WAIT_TIMED_OUT,
                    deletion.get(500, TimeUnit.MILLISECONDS));
            assertEquals(0, physicalDeletes.get());
            assertEquals(ChatRunRegistry.Status.CANCELLING,
                    h.registry.describeExact(1L, first.clientToken()).orElseThrow().status());
            h.clock.set(31_000);
            h.registry.runStaleSweepNow();
            assertFalse(h.registry.beginOrJoin(1L).owner(), "draining cannot release the run slot");
            assertFalse(first.runTerminalSideEffect(physicalDeletes::incrementAndGet));
            h.release.countDown();
            assertTrue(h.write.get(2, TimeUnit.SECONDS));
            h.registry.cancelSessionForDeletion(1L);
            assertEquals(ChatRunRegistry.Status.CANCELLED,
                    h.registry.describeExact(1L, first.clientToken()).orElseThrow().status());
            assertFalse(first.runTerminalSideEffect(physicalDeletes::incrementAndGet));
            assertEquals(0, physicalDeletes.get());
        }
    }

    @Test void throwingWriteReleasesDrainExactlyOnce() throws Exception {
        try (Harness h = new Harness()) {
            ChatRunExecutionContext run = h.registry.beginOrJoin(1L).context();
            assertTrue(run.tryBeginCommit());
            assertThrows(IllegalStateException.class, () -> run.runTerminalSideEffect(() -> {
                throw new IllegalStateException("fixture");
            }));
            h.registry.cancelSessionForDeletion(1L);
            assertEquals(1, h.registry.describeExact(1L, run.clientToken()).orElseThrow().outcome().terminalEventCount());
        }
    }

    static final class Harness implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final ChatRunRegistry registry = new ChatRunRegistry(Executors.newSingleThreadScheduledExecutor(), clock::get, true);
        final ExecutorService pool = Executors.newCachedThreadPool();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<Boolean> write;
        Harness() { registry.replayCapacity = 16; registry.ttlSeconds = 60;
            registry.inflightIdleTimeoutSeconds = 30; registry.deletionCancelWaitMillis = 60; }
        ChatRunExecutionContext blockedWrite() throws Exception {
            ChatRunExecutionContext run = registry.beginOrJoin(1L).context();
            assertTrue(run.tryBeginCommit());
            write = pool.submit(() -> run.runTerminalSideEffect(() -> {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return run;
        }
        public void close() throws Exception { release.countDown(); pool.shutdown();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)); registry.shutdown(); }
    }
}
