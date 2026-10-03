package com.example.lms.uaw.autolearn.ingest;

import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.VectorStoreService.VectorFlushOutcome;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainRagIngestCommitContractTest {
    @TempDir Path tempDir;

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @ParameterizedTest
    @ValueSource(strings = {"backoff", "store_failure", "source_rejected"})
    void nonDurableBatchDoesNotAdvanceCheckpointOrAccepted(String reason) throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(false, 0, 5, reason));
        receipts(store, false, reason, true);
        Path state = tempDir.resolve("state.json");
        TrainRagIngestService service = service(store, state);

        assertEquals(0, service.ingestNewSamples(dataset(5), "synthetic", () -> false));
        assertFalse(Files.exists(state), "failed batch must leave its checkpoint unchanged");
        verify(store, times(1)).flush();
    }

    @Test void exceptionPathStillReturnsFailureWithoutImmediateRetry() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenThrow(new IllegalStateException("synthetic store failure"));
        receipts(store, false, "receipt_pending", true);
        Path state = tempDir.resolve("exception-state.json");

        assertEquals(0, service(store, state).ingestNewSamples(dataset(5), "synthetic", () -> false));
        assertFalse(Files.exists(state));
        verify(store, times(1)).flush();
    }

    @Test void checkpointWriteFailureIsReportedNotSwallowed() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 1, 0, "complete"));
        receipts(store, true, "complete", true);
        Path parentFile = tempDir.resolve("parent-file");
        Files.writeString(parentFile, "synthetic checkpoint obstruction");
        Path state = parentFile.resolve("state.json");

        assertEquals(1, service(store, state).ingestNewSamples(dataset(1), "synthetic", () -> false));
        assertEquals("checkpoint_save_fail", ((java.util.Map<?, ?>) TraceStore.get("uaw.retrain.ingest.summary")).get("reason"));
        assertFalse(Files.exists(state));
    }

    @Test void automaticFailureCannotBeHiddenByAnEmptyFinalFlush() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        receipts(store, false, "source_rejected", false);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 0, 0, "empty"));
        Path state = tempDir.resolve("automatic-failure.json");
        TrainRagIngestService.IngestOutcome result =
                service(store, state).ingestNewSamplesDetailed(dataset(1), "synthetic", () -> false);
        assertEquals(0, result.storedDocs());
        assertEquals(0, result.checkpointConfirmedDocs());
        assertEquals(1, result.sourceRejectedBatches());
        assertFalse(result.complete());
        assertFalse(result.retryable());
        assertFalse(Files.exists(state));
        verify(store, never()).flush();
    }

    @Test void successfulAutomaticFlushAllowsAnEmptyFinalFlush() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        receipts(store, true, "complete", true);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 0, 0, "empty"));
        TrainRagIngestService.IngestOutcome result = service(store, tempDir.resolve("automatic-success.json"))
                .ingestNewSamplesDetailed(dataset(1), "synthetic", () -> false);
        assertEquals(1, result.storedDocs());
        assertEquals(1, result.checkpointConfirmedDocs());
        assertEquals(0, result.unconfirmedDocs());
        assertTrue(result.complete());
    }

    @Test void checkpointFailureSeparatesStoredAndConfirmedCounts() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 1, 0, "complete"));
        receipts(store, true, "complete", true);
        Path parentFile = tempDir.resolve("blocked-parent");
        Files.writeString(parentFile, "fixture");
        TrainRagIngestService.IngestOutcome result = service(store, parentFile.resolve("state.json"))
                .ingestNewSamplesDetailed(dataset(1), "synthetic", () -> false);
        assertEquals(1, result.storedDocs());
        assertEquals(0, result.checkpointConfirmedDocs());
        assertEquals(1, result.unconfirmedDocs());
        assertEquals(0L, result.checkpointOffset());
        assertEquals("checkpoint_save_fail", result.reasonCode());
        assertFalse(result.complete());
    }

    @Test void laterFailurePreservesTheLastConfirmedBatch() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 5, 0, "complete"),
                new VectorFlushOutcome(false, 0, 5, "backoff"));
        var enrolled = new java.util.concurrent.atomic.AtomicInteger();
        when(store.enqueueWithReceipt(anyString(), anyString(), anyString(), any()))
                .thenAnswer(a -> receipt(enrolled.incrementAndGet() <= 5, enrolled.get() <= 5 ? "complete" : "backoff", true));
        Path state = tempDir.resolve("partial-state.json");
        TrainRagIngestService.IngestOutcome result =
                service(store, state, 20).ingestNewSamplesDetailed(dataset(10), "synthetic", () -> false);
        assertEquals(5, result.storedDocs());
        assertEquals(5, result.checkpointConfirmedDocs());
        assertEquals(result.checkpointOffset(),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(state)).get("offset").asLong());
        assertFalse(result.complete());
        verify(store, times(2)).flush();
    }

    @Test void incompleteTailWaitsForNewlineWithoutAdvancingCheckpoint() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 1, 0, "complete"));
        receipts(store, true, "complete", true);
        Path path = dataset(1);
        String row = Files.readString(path).stripTrailing();
        Files.writeString(path, row);
        Path state = tempDir.resolve("tail-state.json");
        TrainRagIngestService service = service(store, state);
        assertEquals(0, service.ingestNewSamples(path, "synthetic", () -> false));
        verifyNoInteractions(store);
        Files.writeString(path, "\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, service.ingestNewSamples(path, "synthetic", () -> false));
    }

    @Test void completePrefixCommitsBeforeAnIncompleteUtf8Tail() throws Exception {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.flush()).thenReturn(new VectorFlushOutcome(true, 1, 0, "complete"));
        receipts(store, true, "complete", true);
        Path path = dataset(1);
        long prefix = Files.size(path);
        byte[] tail = "{\"question\":\"한글\",\"answer\":\"fixture\",\"sessionId\":\"fixture\"}\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, java.util.Arrays.copyOf(tail, 16), java.nio.file.StandardOpenOption.APPEND);
        TrainRagIngestService service = service(store, tempDir.resolve("utf8-state.json"));
        var first = service.ingestNewSamplesDetailed(path, "synthetic", () -> false);
        assertEquals(1, first.storedDocs());
        assertEquals(prefix, first.checkpointOffset());
        Files.write(path, java.util.Arrays.copyOfRange(tail, 16, tail.length),
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, service.ingestNewSamples(path, "synthetic", () -> false));
    }

    @Test void completeInvalidAndBlankRowsCommitWithoutVectorWrites() throws Exception {
        Path path = tempDir.resolve("invalid.jsonl");
        Files.writeString(path, "invalid\n\n");
        VectorStoreService store = mock(VectorStoreService.class);
        var result = service(store, tempDir.resolve("invalid-state.json"))
                .ingestNewSamplesDetailed(path, "synthetic", () -> false);
        assertEquals(Files.size(path), result.checkpointOffset());
        verifyNoInteractions(store);
        assertEquals(1, ((java.util.Map<?, ?>) TraceStore.get("uaw.retrain.ingest.summary")).get("skippedInvalidLines"));
    }

    @Test void readFailureIsNotReportedAsEof() throws Exception {
        var reader = TrainRagIngestService.class.getDeclaredMethod("readUtf8Line", java.io.RandomAccessFile.class);
        reader.setAccessible(true);
        var file = new java.io.RandomAccessFile(dataset(1).toFile(), "r");
        file.close();
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> reader.invoke(null, file));
        assertInstanceOf(java.io.IOException.class, error.getCause());
    }

    private TrainRagIngestService service(VectorStoreService store, Path state) {
        return service(store, state, 10);
    }

    private TrainRagIngestService service(VectorStoreService store, Path state, int maxLines) {
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getRetrain().setIngestStatePath(state.toString());
        props.getRetrain().setMaxIngestLinesPerRun(maxLines);
        return new TrainRagIngestService(store, mock(VectorSidService.class), props);
    }

    private static VectorStoreService.VectorRecordReceipt receipt(boolean durable, String reason, boolean hasTargets) {
        var receipt = mock(VectorStoreService.VectorRecordReceipt.class);
        when(receipt.durable()).thenReturn(durable);
        when(receipt.reasonCode()).thenReturn(reason);
        if (hasTargets) when(receipt.targets()).thenReturn(java.util.Map.of(
                new VectorStoreService.ReceiptTarget("fixture", "", "fixture", "primary"), reason));
        return receipt;
    }

    private static void receipts(VectorStoreService store, boolean durable, String reason, boolean hasTargets) {
        when(store.enqueueWithReceipt(anyString(), anyString(), anyString(), any()))
                .thenAnswer(a -> receipt(durable, reason, hasTargets));
    }

    private Path dataset(int count) throws Exception {
        Path path = tempDir.resolve("samples.jsonl");
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < count; i++) {
            rows.append("{\"question\":\"synthetic ").append(i)
                    .append("\",\"answer\":\"fixture\",\"sessionId\":\"fixture-session\"}\n");
        }
        Files.writeString(path, rows);
        return path;
    }
}
