package ai.abandonware.nova.orch.storage;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDegradedStorageDurabilityTest {

    @TempDir
    Path tempDir;

    @Test
    void directoryPutReportsFinalMoveFailureInsteadOfSilentlyAcceptingIt() throws Exception {
        Path directory = tempDir.resolve("directory-final-move");
        FinalMoveBlockingObjectMapper objectMapper = new FinalMoveBlockingObjectMapper(directory);
        FileDegradedStorage storage = directoryStorage(directory, objectMapper);

        objectMapper.arm();
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> storage.putPending(event("directory-final-move")));

            assertEquals("degraded_storage_directory_write_failed", failure.getMessage());
            assertEquals(0, storage.stats().pendingCount());
        } finally {
            objectMapper.removeBlocker();
        }
    }

    @Test
    void directoryNackDestinationFailureKeepsInflightAndDoesNotCountNack() throws Exception {
        Path directory = tempDir.resolve("directory-nack-destination");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = directoryStorage(directory, objectMapper);
        storage.putPending(event("directory-nack-destination"));
        String token = storage.claim(1).get(0).token();

        Path inflight = directory.resolve(token);
        String pendingName = token.substring(0, token.length() - ".inflight".length()) + ".json";
        Path blockedDestination = directory.resolve(pendingName);
        Path blocker = blockedDestination.resolve("blocker");
        Files.createDirectories(blockedDestination);
        Files.writeString(blocker, "fixture", StandardCharsets.UTF_8);
        try {
            storage.nack(token, "safe_fixture_failure");

            assertTrue(Files.isRegularFile(inflight));
            assertEquals(0, storage.stats().nackTotal());
        } finally {
            Files.deleteIfExists(blocker);
            Files.deleteIfExists(blockedDestination);
        }
    }

    @Test
    void jsonlReleaseRewriteFailureKeepsInflightAndDoesNotCountRelease() throws Exception {
        Path pending = tempDir.resolve("release-rewrite.jsonl");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 0);
        storage.putPending(event("release-rewrite"));
        String token = storage.claim(1).get(0).token();

        Path inflight = inflightPath(pending);
        Path blockedRewrite = inflight.resolveSibling(inflight.getFileName() + ".tmp");
        Path blocker = blockedRewrite.resolve("blocker");
        Files.createDirectories(blockedRewrite);
        Files.writeString(blocker, "fixture", StandardCharsets.UTF_8);
        try {
            storage.release(token);

            assertEquals(List.of(token), readIds(inflight, objectMapper));
            assertEquals(List.of(token), readIds(pending, objectMapper));
            assertEquals(0, storage.stats().releaseTotal());
        } finally {
            Files.deleteIfExists(blocker);
            Files.deleteIfExists(blockedRewrite);
        }
    }

    @Test
    void failedInflightAppendKeepsPendingAndReturnsNoClaim() throws Exception {
        Path pending = tempDir.resolve("claim.jsonl");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 0);
        storage.putPending(event("claim-loss"));

        Path inflight = inflightPath(pending);
        Files.createDirectory(inflight);
        try {
            assertTrue(storage.claim(1).isEmpty());
        } finally {
            Files.delete(inflight);
        }

        assertEquals(1, storage.stats().pendingCount());
    }

    @Test
    void failedNackDestinationKeepsInflightRecoverable() throws Exception {
        Path pending = tempDir.resolve("nack.jsonl");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 0);
        storage.putPending(event("nack-loss"));
        String token = storage.claim(1).get(0).token();

        Files.delete(pending);
        Files.createDirectory(pending);
        try {
            storage.nack(token, "safe_fixture_failure");
        } finally {
            Files.delete(pending);
            Files.createFile(pending);
        }

        assertEquals(List.of(token), readIds(inflightPath(pending), objectMapper));
        assertEquals(0, storage.stats().nackTotal());
    }

    @Test
    void duplicateInflightNackMovesOnlyOneMatchingRow() throws Exception {
        Path pending = tempDir.resolve("duplicate-inflight-nack.jsonl");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 0);
        storage.putPending(event("duplicate-inflight"));
        String token = storage.claim(1).get(0).token();

        Path inflight = inflightPath(pending);
        List<String> inflightLines = Files.readAllLines(inflight, StandardCharsets.UTF_8);
        assertEquals(1, inflightLines.size());
        Files.writeString(
                inflight,
                inflightLines.get(0) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        storage.nack(token, "safe_fixture_failure");

        assertEquals(List.of(token), readIds(pending, objectMapper));
        assertEquals(List.of(token), readIds(inflight, objectMapper));
        assertEquals(1, storage.stats().nackTotal());

        storage.ack(token);
        assertEquals(List.of(token), tokens(storage.claim(10)));
    }

    @Test
    void duplicatePendingIdsAppearOncePerClaimBatchAndRemainRedeliverable() throws Exception {
        Path pending = tempDir.resolve("duplicate.jsonl");
        ObjectMapper objectMapper = configuredMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 0);
        storage.putPending(event("duplicate"));

        List<String> lines = Files.readAllLines(pending, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        String duplicateLine = lines.get(0);
        String duplicateId = objectMapper.readTree(duplicateLine).path("id").asText();
        Files.writeString(
                pending,
                duplicateLine + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        List<String> firstBatch = tokens(storage.claim(10));
        assertEquals(List.of(duplicateId), firstBatch);

        storage.ack(duplicateId);
        assertEquals(List.of(duplicateId), tokens(storage.claim(10)));
    }

    @Test
    void staleRecoveryRemovesOnlySuccessfullyRequeuedRows() throws Exception {
        Path pending = tempDir.resolve("stale.jsonl");
        BlockingSecondWriteObjectMapper objectMapper = new BlockingSecondWriteObjectMapper();
        FileDegradedStorage storage = storage(pending, objectMapper, 1);
        storage.putPending(event("stale-first"));
        storage.putPending(event("stale-second"));
        List<DegradedStorageWithAck.ClaimedPending> claimed = storage.claim(2);
        assertEquals(2, claimed.size());
        String firstToken = claimed.get(0).token();
        String secondToken = claimed.get(1).token();

        Path inflight = inflightPath(pending);
        Files.setLastModifiedTime(inflight, FileTime.from(Instant.EPOCH));
        Path firstRequeue = pending.resolveSibling(pending.getFileName() + ".first-requeue");
        objectMapper.arm();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        DegradedStorageWithAck.OutboxSweepResult result = null;
        try {
            Future<DegradedStorageWithAck.OutboxSweepResult> sweep = worker.submit(storage::sweep);
            assertTrue(objectMapper.awaitSecondWrite());

            Files.move(pending, firstRequeue, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectory(pending);
            objectMapper.releaseSecondWrite();
            result = sweep.get(5, TimeUnit.SECONDS);
        } finally {
            objectMapper.releaseSecondWrite();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            if (Files.isDirectory(pending)) {
                Files.delete(pending);
            }
            if (Files.exists(firstRequeue)) {
                Files.move(firstRequeue, pending, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        assertNotNull(result);
        assertEquals(1, result.recoveredInflight());
        assertEquals(List.of(firstToken), readIds(pending, objectMapper));
        assertEquals(List.of(secondToken), readIds(inflight, objectMapper));
    }

    private static FileDegradedStorage storage(Path pending, ObjectMapper objectMapper, long staleSeconds) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.DegradedStorageProps storageProps = props.getDegradedStorage();
        storageProps.setPath(pending.toString());
        storageProps.setFormat("jsonl");
        storageProps.setTtlSeconds(0);
        storageProps.setMaxFiles(0);
        storageProps.setMaxBytes(0);
        storageProps.setInflightStaleSeconds(staleSeconds);
        storageProps.setEnforceOnWrite(false);
        storageProps.setQuarantineEnabled(false);
        return new FileDegradedStorage(props, objectMapper);
    }

    private static FileDegradedStorage directoryStorage(Path directory, ObjectMapper objectMapper) {
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        NovaOrchestrationProperties.DegradedStorageProps storageProps = props.getDegradedStorage();
        storageProps.setPath(directory.toString());
        storageProps.setFormat("dir");
        storageProps.setTtlSeconds(0);
        storageProps.setMaxFiles(0);
        storageProps.setMaxBytes(0);
        storageProps.setInflightStaleSeconds(0);
        storageProps.setEnforceOnWrite(false);
        storageProps.setQuarantineEnabled(false);
        return new FileDegradedStorage(props, objectMapper);
    }

    private static ObjectMapper configuredMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static PendingMemoryEvent event(String snippet) {
        return new PendingMemoryEvent(
                "session",
                "context",
                "query-hash",
                snippet,
                Instant.now(),
                snippet.length(),
                "ALLOW_NO_MEMORY");
    }

    private static Path inflightPath(Path pending) {
        return pending.resolveSibling(pending.getFileName() + ".inflight");
    }

    private static List<String> tokens(List<DegradedStorageWithAck.ClaimedPending> claimed) {
        List<String> tokens = new ArrayList<>();
        for (DegradedStorageWithAck.ClaimedPending item : claimed) {
            tokens.add(item.token());
        }
        return tokens;
    }

    private static List<String> readIds(Path path, ObjectMapper objectMapper) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(line);
            ids.add(node.path("id").asText());
        }
        return ids;
    }

    private static final class BlockingSecondWriteObjectMapper extends ObjectMapper {
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private final AtomicInteger armedWrites = new AtomicInteger();
        private final CountDownLatch secondWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSecondWrite = new CountDownLatch(1);

        private BlockingSecondWriteObjectMapper() {
            findAndRegisterModules();
        }

        void arm() {
            armedWrites.set(0);
            armed.set(true);
        }

        boolean awaitSecondWrite() throws InterruptedException {
            return secondWriteStarted.await(5, TimeUnit.SECONDS);
        }

        void releaseSecondWrite() {
            releaseSecondWrite.countDown();
        }

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            if (armed.get() && armedWrites.incrementAndGet() == 2) {
                secondWriteStarted.countDown();
                try {
                    if (!releaseSecondWrite.await(5, TimeUnit.SECONDS)) {
                        throw JsonMappingException.fromUnexpectedIOE(new IOException("bounded fixture timeout"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw JsonMappingException.fromUnexpectedIOE(new IOException("fixture interrupted", e));
                }
            }
            return super.writeValueAsString(value);
        }
    }

    private static final class FinalMoveBlockingObjectMapper extends ObjectMapper {
        private final Path directory;
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private Path blockedDestination;

        private FinalMoveBlockingObjectMapper(Path directory) {
            this.directory = directory;
            findAndRegisterModules();
        }

        void arm() {
            armed.set(true);
        }

        void removeBlocker() throws IOException {
            if (blockedDestination == null) {
                return;
            }
            Files.deleteIfExists(blockedDestination.resolve("blocker"));
            Files.deleteIfExists(blockedDestination);
        }

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            String json = super.writeValueAsString(value);
            if (value != null
                    && value.getClass().getSimpleName().equals("OutboxEnvelope")
                    && armed.compareAndSet(true, false)) {
                try {
                    JsonNode node = super.readTree(json);
                    blockedDestination = directory.resolve(
                            "outbox_" + node.path("createdAtEpochMs").asLong()
                                    + "_" + node.path("id").asText() + ".json");
                    Files.createDirectories(blockedDestination);
                    Files.writeString(
                            blockedDestination.resolve("blocker"),
                            "fixture",
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw JsonMappingException.fromUnexpectedIOE(e);
                }
            }
            return json;
        }
    }
}
