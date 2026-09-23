package ai.abandonware.nova.orch.storage;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDegradedStorageConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void twoInstancesClaimTheSameJsonlEnvelopeAtMostOnce() throws Exception {
        Path pending = tempDir.resolve("shared-claim.jsonl");
        CountDownLatch bothClaimReadersReady = new CountDownLatch(2);
        FileDegradedStorage first = storage(
                pending,
                new CoordinatedClaimReadObjectMapper(bothClaimReadersReady));
        FileDegradedStorage second = storage(
                pending,
                new CoordinatedClaimReadObjectMapper(bothClaimReadersReady));
        first.putPending(event("shared-claim"));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<DegradedStorageWithAck.ClaimedPending>> firstClaim = workers.submit(() -> {
                start.await();
                return first.claim(1);
            });
            Future<List<DegradedStorageWithAck.ClaimedPending>> secondClaim = workers.submit(() -> {
                start.await();
                return second.claim(1);
            });

            start.countDown();
            List<DegradedStorageWithAck.ClaimedPending> firstResult = firstClaim.get(5, TimeUnit.SECONDS);
            List<DegradedStorageWithAck.ClaimedPending> secondResult = secondClaim.get(5, TimeUnit.SECONDS);

            assertEquals(1, firstResult.size() + secondResult.size());
            Set<String> tokens = new HashSet<>();
            firstResult.forEach(item -> tokens.add(item.token()));
            secondResult.forEach(item -> tokens.add(item.token()));
            assertEquals(1, tokens.size());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentJsonlPutDoesNotWaitForFullOnWriteSweep() throws Exception {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.DegradedStorageProps storageProps = props.getDegradedStorage();
        storageProps.setPath(tempDir.resolve("degraded-memory.jsonl").toString());
        storageProps.setFormat("jsonl");
        storageProps.setEnforceOnWrite(true);
        storageProps.setTtlSeconds(0);

        BlockingReadObjectMapper objectMapper = new BlockingReadObjectMapper();
        FileDegradedStorage storage = new FileDegradedStorage(props, objectMapper);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstPut = workers.submit(() -> storage.putPending(event("first-snippet")));
            assertTrue(objectMapper.awaitSweepRead(), "the first put must reach the full JSONL sweep");

            Future<?> secondPut = workers.submit(() -> storage.putPending(event("second-snippet")));
            try {
                assertDoesNotThrow(
                        () -> secondPut.get(750, TimeUnit.MILLISECONDS),
                        "a second durable put must not wait for an unrelated full-file sweep");
            } finally {
                objectMapper.releaseSweepRead();
            }

            firstPut.get(5, TimeUnit.SECONDS);

            List<DegradedStorageWithAck.ClaimedPending> claimed = storage.claim(10);
            Set<String> snippets = new HashSet<>();
            for (DegradedStorageWithAck.ClaimedPending item : claimed) {
                snippets.add(item.event().answerSnippet());
            }
            assertEquals(Set.of("first-snippet", "second-snippet"), snippets);
        } finally {
            objectMapper.releaseSweepRead();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static PendingMemoryEvent event(String snippet) {
        return new PendingMemoryEvent(
                "session",
                "context",
                "query-hash",
                snippet,
                Instant.parse("2026-08-25T00:00:00Z"),
                snippet.length(),
                "ALLOW_NO_MEMORY");
    }

    private static FileDegradedStorage storage(Path pending, ObjectMapper objectMapper) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.DegradedStorageProps storageProps = props.getDegradedStorage();
        storageProps.setPath(pending.toString());
        storageProps.setFormat("jsonl");
        storageProps.setTtlSeconds(0);
        storageProps.setMaxFiles(0);
        storageProps.setMaxBytes(0);
        storageProps.setInflightStaleSeconds(0);
        storageProps.setEnforceOnWrite(false);
        storageProps.setQuarantineEnabled(false);
        return new FileDegradedStorage(props, objectMapper);
    }

    private static final class BlockingReadObjectMapper extends ObjectMapper {
        private final AtomicBoolean blockNextRead = new AtomicBoolean(true);
        private final CountDownLatch sweepReadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSweepRead = new CountDownLatch(1);

        private BlockingReadObjectMapper() {
            findAndRegisterModules();
        }

        @Override
        public <T> T readValue(String content, Class<T> valueType)
                throws JsonProcessingException, JsonMappingException {
            if (blockNextRead.compareAndSet(true, false)) {
                sweepReadStarted.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseSweepRead.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.readValue(content, valueType);
        }

        boolean awaitSweepRead() throws InterruptedException {
            return sweepReadStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseSweepRead() {
            releaseSweepRead.countDown();
        }
    }

    private static final class CoordinatedClaimReadObjectMapper extends ObjectMapper {
        private final CountDownLatch bothClaimReadersReady;
        private final AtomicBoolean coordinated = new AtomicBoolean(false);

        private CoordinatedClaimReadObjectMapper(CountDownLatch bothClaimReadersReady) {
            this.bothClaimReadersReady = bothClaimReadersReady;
            findAndRegisterModules();
        }

        @Override
        public <T> T readValue(String content, Class<T> valueType)
                throws JsonProcessingException, JsonMappingException {
            if (valueType != null
                    && valueType.getSimpleName().equals("OutboxEnvelope")
                    && coordinated.compareAndSet(false, true)
                    && calledFromClaimJsonl()) {
                bothClaimReadersReady.countDown();
                try {
                    bothClaimReadersReady.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw JsonMappingException.fromUnexpectedIOE(
                            new java.io.IOException("fixture interrupted", e));
                }
            }
            return super.readValue(content, valueType);
        }

        private static boolean calledFromClaimJsonl() {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(frames -> frames.anyMatch(frame ->
                    frame.getDeclaringClass().equals(FileDegradedStorage.class)
                            && frame.getMethodName().equals("claimJsonl")));
        }
    }
}
