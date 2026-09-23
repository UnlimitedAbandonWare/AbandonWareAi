package com.example.lms.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlLifecycleReceiptStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void committedReceiptSurvivesStoreRestartAndLatestReturnsTerminalState() {
        Path path = tempDir.resolve("lifecycle.jsonl");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String subjectHash = DurableLifecycleReceiptStore.hash("subject", "feedback-subject");
        String payloadHash = DurableLifecycleReceiptStore.hash("payload", "feedback-payload");

        JsonlLifecycleReceiptStore first = new JsonlLifecycleReceiptStore(path, objectMapper);
        first.record(receipt(
                DurableLifecycleReceiptStore.Lifecycle.FEEDBACK,
                subjectHash,
                payloadHash,
                DurableLifecycleReceiptStore.State.PENDING,
                1_000L,
                DurableLifecycleReceiptStore.Reason.NONE));
        first.record(receipt(
                DurableLifecycleReceiptStore.Lifecycle.FEEDBACK,
                subjectHash,
                payloadHash,
                DurableLifecycleReceiptStore.State.COMMITTED,
                2_000L,
                DurableLifecycleReceiptStore.Reason.NONE));

        JsonlLifecycleReceiptStore restarted = new JsonlLifecycleReceiptStore(path, objectMapper);

        assertEquals(
                DurableLifecycleReceiptStore.State.COMMITTED,
                restarted.find(DurableLifecycleReceiptStore.Lifecycle.FEEDBACK, subjectHash)
                        .orElseThrow()
                        .state());
        assertEquals(
                2_000L,
                restarted.latest(DurableLifecycleReceiptStore.Lifecycle.FEEDBACK)
                        .orElseThrow()
                        .atEpochMs());
    }

    @Test
    void jsonlPersistsOnlyAllowlistedHashStateTimeAndReasonFields() throws Exception {
        Path path = tempDir.resolve("privacy.jsonl");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String privateSubject = "raw-feedback-comment-private-sentinel";
        String privatePayload = "C:\\private\\attachment-name.txt?n8nBody=private-sentinel";
        String subjectHash = DurableLifecycleReceiptStore.hash("subject", privateSubject);
        String payloadHash = DurableLifecycleReceiptStore.hash("payload", privatePayload);
        JsonlLifecycleReceiptStore store = new JsonlLifecycleReceiptStore(path, objectMapper);

        DurableLifecycleReceiptStore.Receipt receipt = receipt(
                DurableLifecycleReceiptStore.Lifecycle.ATTACHMENT,
                subjectHash,
                payloadHash,
                DurableLifecycleReceiptStore.State.DELETE_FAILED,
                3_000L,
                DurableLifecycleReceiptStore.Reason.STORAGE_DELETE_FAILED);
        store.record(receipt);

        String persisted = Files.readString(path, StandardCharsets.UTF_8);
        assertFalse(persisted.contains(privateSubject));
        assertFalse(persisted.contains(privatePayload));
        JsonNode row = objectMapper.readTree(persisted.lines().findFirst().orElseThrow());
        Set<String> fields = new HashSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of(
                "schemaVersion",
                "lifecycle",
                "subjectHash",
                "payloadHash",
                "state",
                "atEpochMs",
                "reason"), fields);
        assertEquals(subjectHash, row.path("subjectHash").asText());
        assertEquals(payloadHash, row.path("payloadHash").asText());
        assertTrue(DurableLifecycleReceiptStore.receiptHash(receipt).matches("[0-9a-f]{64}"));
    }

    @Test
    void invalidHashOrLifecycleStateCombinationIsRejectedBeforeDiskWrite() {
        Path path = tempDir.resolve("invalid.jsonl");
        JsonlLifecycleReceiptStore store = new JsonlLifecycleReceiptStore(
                path,
                new ObjectMapper().findAndRegisterModules());
        String hash = DurableLifecycleReceiptStore.hash("payload", "safe");

        assertThrows(IllegalArgumentException.class, () -> store.record(new DurableLifecycleReceiptStore.Receipt(
                1,
                DurableLifecycleReceiptStore.Lifecycle.N8N,
                "not-a-hash",
                hash,
                DurableLifecycleReceiptStore.State.INTENT,
                1_000L,
                DurableLifecycleReceiptStore.Reason.NONE)));
        assertThrows(IllegalArgumentException.class, () -> store.record(new DurableLifecycleReceiptStore.Receipt(
                1,
                DurableLifecycleReceiptStore.Lifecycle.FEEDBACK,
                hash,
                hash,
                DurableLifecycleReceiptStore.State.DELETED,
                1_000L,
                DurableLifecycleReceiptStore.Reason.NONE)));
        assertFalse(Files.exists(path));
    }

    private static DurableLifecycleReceiptStore.Receipt receipt(
            DurableLifecycleReceiptStore.Lifecycle lifecycle,
            String subjectHash,
            String payloadHash,
            DurableLifecycleReceiptStore.State state,
            long atEpochMs,
            DurableLifecycleReceiptStore.Reason reason) {
        return new DurableLifecycleReceiptStore.Receipt(
                1,
                lifecycle,
                subjectHash,
                payloadHash,
                state,
                atEpochMs,
                reason);
    }
}
