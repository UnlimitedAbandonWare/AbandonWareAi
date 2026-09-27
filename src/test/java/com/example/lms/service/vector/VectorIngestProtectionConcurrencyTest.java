package com.example.lms.service.vector;

import com.example.lms.service.rag.LangChainRAGService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class VectorIngestProtectionConcurrencyTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void simultaneousSameSidFailuresNeverLoseTheThresholdTransition() throws Exception {
        VectorIngestProtectionService service = new VectorIngestProtectionService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "globalOnly", true);
        ReflectionTestUtils.setField(service, "threshold", 2);
        ReflectionTestUtils.setField(service, "windowMs", 60_000L);
        ReflectionTestUtils.setField(service, "resetMs", 60_000L);
        ReflectionTestUtils.setField(service, "quarantineMs", 60_000L);

        int iterations = 20_000;
        Phaser phases = new Phaser(3);
        AtomicBoolean lostTransition = new AtomicBoolean();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        RuntimeException matchingFailure = new RuntimeException("vector upsert error");

        Runnable worker = () -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    phases.arriveAndAwaitAdvance();
                    service.recordIfMatches(
                            LangChainRAGService.GLOBAL_SID,
                            matchingFailure,
                            "vector_flush");
                    phases.arriveAndAwaitAdvance();
                }
            } catch (Throwable failure) {
                workerFailure.compareAndSet(null, failure);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(worker);
            executor.submit(worker);
            for (int i = 0; i < iterations; i++) {
                service.clearQuarantine(LangChainRAGService.GLOBAL_SID);
                phases.arriveAndAwaitAdvance();
                phases.arriveAndAwaitAdvance();
                if (!service.isQuarantineActive(LangChainRAGService.GLOBAL_SID)) {
                    lostTransition.set(true);
                }
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertNull(workerFailure.get(), "workers must complete without an internal failure");
        assertFalse(lostTransition.get(),
                "two simultaneous matching failures must always cross threshold two");
    }
}
