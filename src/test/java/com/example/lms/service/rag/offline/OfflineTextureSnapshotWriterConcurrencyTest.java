package com.example.lms.service.rag.offline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OfflineTextureSnapshotWriterConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void sameQueryWritesInOneMillisecondRetainDistinctSnapshots() throws Exception {
        OfflineTextureProperties props = props(2);
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);
        Instant fixed = Instant.parse("2026-08-28T00:00:00Z");

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(fixed);
            writer.writeFrom("same-query", null, Map.of());
            writer.writeFrom("same-query", null, Map.of());
        }

        List<Map<String, Object>> rows = manifestRows(props, mapper);
        assertEquals(2, rows.size());
        assertEquals(2, new HashSet<>(rows.stream().map(row -> row.get("snapshotId")).toList()).size());
        assertAllReferencedSnapshotsExist(props, rows);
    }

    @Test
    void trimmingDoesNotLetBlankLinesConsumeSnapshotCapacity() throws Exception {
        OfflineTextureProperties props = props(2);
        ObjectMapper mapper = new ObjectMapper();
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);

        for (int i = 1; i <= 4; i++) {
            writer.writeFrom("query-" + i, null, Map.of());
        }

        List<Map<String, Object>> rows = manifestRows(props, mapper);
        assertEquals(2, rows.size());
        assertEquals(2L, snapshotFileCount(props));
        assertAllReferencedSnapshotsExist(props, rows);
    }

    @Test
    void concurrentTrimsNeverPublishAReferenceToADeletedSnapshot() throws Exception {
        OfflineTextureProperties props = props(1);
        BlockingCleanupMapper mapper = new BlockingCleanupMapper();
        Path manifest = Path.of(props.getManifestPath());
        Path oldSnapshot = Path.of(props.getSnapshotDir()).resolve("old.json");
        Files.createDirectories(oldSnapshot.getParent());
        Files.writeString(oldSnapshot, "{}", StandardCharsets.UTF_8);
        Files.writeString(
                manifest,
                mapper.writeValueAsString(Map.of(
                        "snapshotId", "old",
                        "snapshotPath", "snapshots/old.json")) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(props, mapper);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        CountDownLatch secondFinished = new CountDownLatch(1);
        Thread first = new Thread(
                () -> runWrite(writer, "first", firstError, null),
                "offline-texture-first-writer");
        Thread second = new Thread(
                () -> runWrite(writer, "second", secondError, secondFinished),
                "offline-texture-second-writer");

        try {
            first.start();
            assertTrue(mapper.firstCleanupEntered.await(5, TimeUnit.SECONDS));
            second.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && secondFinished.getCount() != 0L
                    && second.getState() != Thread.State.BLOCKED) {
                Thread.onSpinWait();
            }
            assertTrue(secondFinished.getCount() == 0L || second.getState() == Thread.State.BLOCKED,
                    "second writer must either expose the unlocked race or wait on writer serialization");
        } finally {
            mapper.releaseFirstCleanup.countDown();
            first.join(TimeUnit.SECONDS.toMillis(5));
            second.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(firstError.get());
        assertNull(secondError.get());
        List<Map<String, Object>> rows = manifestRows(props, mapper);
        assertEquals(1, rows.size());
        assertAllReferencedSnapshotsExist(props, rows);
    }

    @Test
    void failedManifestSerializationPreservesPriorManifestWithoutANewOrphan() throws Exception {
        OfflineTextureProperties props = props(2);
        ObjectMapper setupMapper = new ObjectMapper();
        Path manifest = Path.of(props.getManifestPath());
        Path oldSnapshot = Path.of(props.getSnapshotDir()).resolve("old.json");
        Files.createDirectories(oldSnapshot.getParent());
        Files.writeString(oldSnapshot, "{}", StandardCharsets.UTF_8);
        String previous = setupMapper.writeValueAsString(Map.of(
                "snapshotId", "old",
                "snapshotPath", "snapshots/old.json")) + System.lineSeparator();
        Files.writeString(manifest, previous, StandardCharsets.UTF_8);
        OfflineTextureSnapshotWriter writer = new OfflineTextureSnapshotWriter(
                props,
                new FailOnSecondSerializationMapper());

        writer.writeFrom("new-query", null, Map.of());

        assertEquals(previous, Files.readString(manifest, StandardCharsets.UTF_8));
        assertEquals(1L, snapshotFileCount(props));
        assertTrue(Files.isRegularFile(oldSnapshot));
    }

    private OfflineTextureProperties props(int maxSnapshots) {
        OfflineTextureProperties props = new OfflineTextureProperties();
        props.setWriteEnabled(true);
        props.setMaxSnapshots(maxSnapshots);
        props.setManifestPath(tempDir.resolve("offline_texture_manifest.jsonl").toString());
        props.setSnapshotDir(tempDir.resolve("snapshots").toString());
        return props;
    }

    private static void runWrite(
            OfflineTextureSnapshotWriter writer,
            String query,
            AtomicReference<Throwable> error,
            CountDownLatch finished) {
        try {
            writer.writeFrom(query, null, Map.of());
        } catch (Throwable failure) {
            error.set(failure);
        } finally {
            if (finished != null) {
                finished.countDown();
            }
        }
    }

    private static List<Map<String, Object>> manifestRows(
            OfflineTextureProperties props,
            ObjectMapper mapper) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(props.getManifestPath()), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                rows.add(mapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
            }
        }
        return rows;
    }

    private static long snapshotFileCount(OfflineTextureProperties props) throws Exception {
        try (var files = Files.list(Path.of(props.getSnapshotDir()))) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static void assertAllReferencedSnapshotsExist(
            OfflineTextureProperties props,
            List<Map<String, Object>> rows) {
        Path manifestParent = Path.of(props.getManifestPath()).getParent();
        for (Map<String, Object> row : rows) {
            Path referenced = manifestParent.resolve(String.valueOf(row.get("snapshotPath"))).normalize();
            assertTrue(Files.isRegularFile(referenced), "manifest references missing snapshot " + row.get("snapshotId"));
        }
    }

    private static final class BlockingCleanupMapper extends ObjectMapper {
        private final CountDownLatch firstCleanupEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCleanup = new CountDownLatch(1);
        private final AtomicBoolean blocked = new AtomicBoolean();

        @Override
        public <T> T readValue(String content, Class<T> valueType) throws JsonProcessingException {
            if (Thread.currentThread().getName().equals("offline-texture-first-writer")
                    && content.contains("\"snapshotId\":\"old\"")
                    && blocked.compareAndSet(false, true)) {
                firstCleanupEntered.countDown();
                try {
                    if (!releaseFirstCleanup.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first cleanup release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("first cleanup interrupted", interrupted);
                }
            }
            return super.readValue(content, valueType);
        }
    }

    private static final class FailOnSecondSerializationMapper extends ObjectMapper {
        private int calls;

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            calls++;
            if (calls == 2) {
                throw new JsonProcessingException("forced manifest serialization failure") {};
            }
            return super.writeValueAsString(value);
        }
    }
}
