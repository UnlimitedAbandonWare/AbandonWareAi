package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FinalizedMemoryPersistenceCancellationFenceTest {
    @Test void orderedStagesCommitOnlyOnceForExactOwner() throws Exception {
        try (var h = new ChatRunRegistryTerminalIsolationTest.Harness()) {
            var run = h.registry.beginOrJoin(1L).context();
            List<String> calls = new ArrayList<>();
            FinalizedMemoryPersistence.persist(run, CancellationException::new, (stage, error) -> fail(error),
                    stage("first", () -> calls.add("first")), stage("second", () -> calls.add("second")));
            assertEquals(List.of("first", "second"), calls);
            assertThrows(CancellationException.class, () -> FinalizedMemoryPersistence.persist(run,
                    CancellationException::new, (s, e) -> fail(e), stage("duplicate", () -> calls.add("duplicate"))));
            assertEquals(2, calls.size());
        }
    }

    @Test void deletionDuringStageDrainsItAndRejectsEveryLaterStage() throws Exception {
        try (var h = new ChatRunRegistryTerminalIsolationTest.Harness()) {
            var run = h.registry.beginOrJoin(1L).context();
            AtomicInteger laterWrites = new AtomicInteger();
            Future<?> persistence = h.pool.submit(() -> FinalizedMemoryPersistence.persist(run,
                    CancellationException::new, (s, e) -> fail(e), stage("active", () -> {
                        h.started.countDown();
                        try { assertTrue(h.release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    }), stage("late", laterWrites::incrementAndGet)));
            assertTrue(h.started.await(2, TimeUnit.SECONDS));
            Future<Boolean> deletion = h.pool.submit(() -> {
                assertThrows(ChatRunRegistry.SessionDeletionFenceException.class,
                        () -> h.registry.cancelSessionForDeletion(1L)); return true;
            });
            assertTrue(deletion.get(500, TimeUnit.MILLISECONDS));
            h.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> persistence.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            h.registry.cancelSessionForDeletion(1L);
            assertEquals(0, laterWrites.get());
        }
    }

    @Test void cancellationBeforeCommitNeverWrites() throws Exception {
        try (var h = new ChatRunRegistryTerminalIsolationTest.Harness()) {
            var run = h.registry.beginOrJoin(1L).context();
            assertTrue(h.registry.cancelExact(1L, run.clientToken()));
            AtomicInteger writes = new AtomicInteger();
            assertThrows(CancellationException.class, () -> FinalizedMemoryPersistence.persist(run,
                    CancellationException::new, (s, e) -> fail(e), stage("late", writes::incrementAndGet)));
            assertEquals(0, writes.get());
        }
    }
    private static FinalizedMemoryPersistence.Stage stage(String name, Runnable action) {
        return new FinalizedMemoryPersistence.Stage(name, action);
    }
}
