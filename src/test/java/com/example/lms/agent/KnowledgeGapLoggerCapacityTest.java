package com.example.lms.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGapLoggerCapacityTest {

    @Test
    void eventPayloadsAreBoundedBeforeTheyEnterTheQueue() {
        KnowledgeGapLogger logger = new KnowledgeGapLogger();

        logger.logEvent(
                "q".repeat(5_000),
                "d".repeat(500),
                "s".repeat(1_000),
                "i".repeat(1_000));

        KnowledgeGapLogger.GapEvent event = logger.poll().orElseThrow();
        assertEquals(4_096, event.getQueryLength());
        assertEquals(256, event.getDomainLength());
        assertEquals(512, event.getSubjectLength());
        assertEquals(512, event.getIntentLength());

        logger.logEvent("x".repeat(4_095) + "😀tail", "domain", "subject", "intent");
        String boundedQuery = logger.poll().orElseThrow().getQuery();
        assertEquals(4_095, boundedQuery.length());
        assertFalse(Character.isHighSurrogate(boundedQuery.charAt(boundedQuery.length() - 1)));
    }

    @Test
    void retentionEvictsOldestEventsAndKeepsRecentTailOrder() {
        KnowledgeGapLogger logger = new KnowledgeGapLogger();

        for (int i = 0; i < 1_029; i++) {
            logger.logEvent("q-" + i, "domain", "subject", "intent");
        }

        List<KnowledgeGapLogger.GapEvent> snapshot = logger.snapshot();
        assertEquals(1_024, snapshot.size());
        assertEquals("q-5", snapshot.get(0).getQuery());
        assertEquals("q-1028", snapshot.get(snapshot.size() - 1).getQuery());
        assertEquals(
                List.of("q-1026", "q-1027", "q-1028"),
                logger.snapshotRecent(3).stream().map(KnowledgeGapLogger.GapEvent::getQuery).toList());
        assertEquals("q-5", logger.poll().orElseThrow().getQuery());
    }

    @Test
    void concurrentProducersCannotExceedTheRetentionBound() throws Exception {
        KnowledgeGapLogger logger = new KnowledgeGapLogger();
        int producers = 4;
        int perProducer = 400;
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        CountDownLatch ready = new CountDownLatch(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);

        try {
            for (int producer = 0; producer < producers; producer++) {
                int producerId = producer;
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < perProducer; i++) {
                            logger.logEvent("q-" + producerId + "-" + i, "domain", "subject", "intent");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1_024, logger.snapshot().size());
        assertFalse(logger.snapshotRecent(32).isEmpty());
        assertTrue(logger.snapshotRecent(32).size() <= 32);
    }
}
