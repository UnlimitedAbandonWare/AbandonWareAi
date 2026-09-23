package com.example.lms.service.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizedMemoryPersistenceTest {

    @Test
    void cancelledRunNeverEntersLearningOrPersistence() {
        ChatRunRegistry registry = registry();
        try {
            ChatRunExecutionContext run = registry.beginOrJoin(901L).context();
            assertTrue(registry.cancelExact(901L, run.clientToken()));
            AtomicInteger effects = new AtomicInteger();

            assertThrows(CancellationException.class, () -> FinalizedMemoryPersistence.persist(
                    run,
                    CancellationException::new,
                    (stage, failure) -> { },
                    stage("learning", effects::incrementAndGet),
                    stage("memory", effects::incrementAndGet),
                    stage("understanding", effects::incrementAndGet),
                    stage("reinforcement", effects::incrementAndGet)));

            assertEquals(0, effects.get());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void cancellationFromLearningStopsEveryLaterPersistenceStage() {
        ChatRunRegistry registry = registry();
        try {
            ChatRunExecutionContext run = registry.beginOrJoin(902L).context();
            AtomicInteger memory = new AtomicInteger();
            AtomicInteger understanding = new AtomicInteger();
            AtomicInteger reinforcement = new AtomicInteger();
            List<String> suppressed = new ArrayList<>();

            assertThrows(CancellationException.class, () -> FinalizedMemoryPersistence.persist(
                    run,
                    CancellationException::new,
                    (stage, failure) -> suppressed.add(stage),
                    stage("learning", () -> { throw new CancellationException("provider cancelled"); }),
                    stage("memory", memory::incrementAndGet),
                    stage("understanding", understanding::incrementAndGet),
                    stage("reinforcement", reinforcement::incrementAndGet)));

            assertEquals(0, memory.get());
            assertEquals(0, understanding.get());
            assertEquals(0, reinforcement.get());
            assertTrue(suppressed.isEmpty(), "terminal cancellation must not be recorded as a fail-soft persistence error");
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void interruptionFromLearningRestoresFlagAndStopsEveryLaterPersistenceStage() {
        ChatRunRegistry registry = registry();
        try {
            ChatRunExecutionContext run = registry.beginOrJoin(903L).context();
            AtomicInteger laterEffects = new AtomicInteger();

            CancellationException failure = assertThrows(CancellationException.class,
                    () -> FinalizedMemoryPersistence.persist(
                            run,
                            CancellationException::new,
                            (stage, ignored) -> { },
                            stage("learning", () -> sneakyThrow(new InterruptedException("learning interrupted"))),
                            stage("memory", laterEffects::incrementAndGet),
                            stage("understanding", laterEffects::incrementAndGet),
                            stage("reinforcement", laterEffects::incrementAndGet)));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertEquals(0, laterEffects.get());
        } finally {
            Thread.interrupted();
            registry.shutdown();
        }
    }

    @Test
    void ordinaryStageFailureIsSuppressedAndLaterStagesContinue() {
        ChatRunRegistry registry = registry();
        try {
            ChatRunExecutionContext run = registry.beginOrJoin(904L).context();
            AtomicInteger laterEffects = new AtomicInteger();
            List<String> suppressed = new ArrayList<>();

            FinalizedMemoryPersistence.persist(
                    run,
                    CancellationException::new,
                    (stage, failure) -> suppressed.add(stage + ":" + failure.getClass().getSimpleName()),
                    stage("learning", () -> { throw new IllegalStateException("fail-soft"); }),
                    stage("memory", laterEffects::incrementAndGet),
                    stage("understanding", laterEffects::incrementAndGet),
                    stage("reinforcement", laterEffects::incrementAndGet));

            assertEquals(List.of("learning:IllegalStateException"), suppressed);
            assertEquals(3, laterEffects.get());
        } finally {
            registry.shutdown();
        }
    }

    private static FinalizedMemoryPersistence.Stage stage(String name, Runnable action) {
        return new FinalizedMemoryPersistence.Stage(name, action);
    }

    private static ChatRunRegistry registry() {
        ChatRunRegistry registry = new ChatRunRegistry();
        registry.replayCapacity = 16;
        registry.ttlSeconds = 60;
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
