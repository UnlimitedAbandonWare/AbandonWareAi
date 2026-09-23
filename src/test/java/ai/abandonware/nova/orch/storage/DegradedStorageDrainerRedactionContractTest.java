package ai.abandonware.nova.orch.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class DegradedStorageDrainerRedactionContractTest {

    @Test
    void degradedStorageTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();
        try {
            DegradedStorageTraceSuppressions.trace(
                    "safeSize",
                    new NumberFormatException("ownerToken=raw-secret"));

            assertEquals("invalid_number",
                    TraceStore.get("nova.degraded.storage.suppressed.safeSize.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("NumberFormatException"), trace);
            assertFalse(trace.contains("ownerToken=raw-secret"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void degradedStorageTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        TraceStore.clear();
        try {
            String rawStage = "safeSize " + com.example.lms.test.SecretFixtures.openAiKey();
            DegradedStorageTraceSuppressions.trace(
                    rawStage,
                    new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

            Object safeStage = TraceStore.get("nova.degraded.storage.suppressed.stage");
            assertTrue(String.valueOf(safeStage).startsWith("hash:"));
            assertEquals(Boolean.TRUE, TraceStore.get("nova.degraded.storage.suppressed." + safeStage));
            assertEquals("IllegalStateException", TraceStore.get("nova.degraded.storage.suppressed.errorType"));
            assertEquals("IllegalStateException",
                    TraceStore.get("nova.degraded.storage.suppressed." + safeStage + ".errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void drainerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/storage/DegradedStorageDrainer.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "degraded storage drain housekeeping needs stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void nackReasonDoesNotPersistThrowableToString() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/storage/DegradedStorageDrainer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("ackable.nack(c.token(), ex.toString());"));
        assertFalse(source.contains(
                "ackable.nack(c.token(), com.example.lms.trace.SafeRedactor.safeMessage(ex.getMessage(), 240));"));
        assertTrue(source.contains(
                "ackable.nack(c.token(), String.format(\"errorHash=%s errorLength=%d\","));
        assertTrue(source.contains(
                "com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)));"));
        assertFalse(source.contains(
                "log.warn(\"[degraded-drain] promote failed; kept in outbox. reason={} err={}\", e.reason(),"));
        assertFalse(source.contains(
                "log.warn(\"[degraded-drain] promote failed; requeued. reason={} err={}\", e.reason(),"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(source.contains("String.valueOf(ex)"));
        assertTrue(source.contains(
                "log.warn(\"[degraded-drain] promote failed; kept in outbox. reason={} errorHash={} errorLength={}\","));
        assertTrue(source.contains(
                "log.warn(\"[degraded-drain] promote failed; requeued. reason={} errorHash={} errorLength={}\","));
        assertTrue(source.contains("com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex))"));
        assertTrue(source.contains("messageLength(ex)"));
        assertTrue(source.contains("logSuppressed(\"promoted.failure\", ex);"));
        assertTrue(source.contains("logSuppressed(\"promoted.nack\", ignore);"));
        assertTrue(source.contains("logSuppressed(\"config.getBool\", ignore);"));
    }

    @Test
    void promotedSourceTagDoesNotCarryRawPendingReason() {
        String rawReason = "private student timeout retry path";
        String snippet = "Evidence with enough detail for promotion.";
        SingleDrainStorage storage = new SingleDrainStorage(new PendingMemoryEvent(
                "sid-1",
                null,
                "abc123",
                snippet,
                Instant.EPOCH,
                snippet.length(),
                rawReason));
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getDegradedStorage().getDrain().setEnabled(true);
        props.getDegradedStorage().getDrain().setRequireEvidence(false);
        props.getDegradedStorage().getDrain().setMinSnippetLen(1);

        new DegradedStorageDrainer(storage, memory, props,
                new MockEnvironment().withProperty("memory.enabled", "true")).tick();

        ArgumentCaptor<String> sourceTag = ArgumentCaptor.forClass(String.class);
        verify(memory).reinforceWithSnippet(eq("sid-1"), eq("qhash:abc123"), eq(snippet),
                sourceTag.capture(), anyDouble());
        assertTrue(sourceTag.getValue().startsWith("degraded:hash:"), sourceTag.getValue());
        assertFalse(sourceTag.getValue().contains(rawReason), sourceTag.getValue());
        assertFalse(sourceTag.getValue().contains("student"), sourceTag.getValue());
    }

    @Test
    void promotedSnippetDoesNotCarryRawSecretShape() {
        String syntheticKey = "sk-" + "123456789012345678901234";
        String snippet = "Evidence https://example.test/docs ownerToken=raw-token api_key=" + syntheticKey;
        SingleDrainStorage storage = new SingleDrainStorage(new PendingMemoryEvent(
                "sid-secret",
                null,
                "qhash-secret",
                snippet,
                Instant.EPOCH,
                snippet.length(),
                "ALLOW_NO_MEMORY"));
        MemoryReinforcementService memory = mock(MemoryReinforcementService.class);
        NovaOrchestrationProperties props = new NovaOrchestrationProperties();
        props.getDegradedStorage().getDrain().setEnabled(true);
        props.getDegradedStorage().getDrain().setRequireEvidence(false);
        props.getDegradedStorage().getDrain().setMinSnippetLen(1);

        new DegradedStorageDrainer(storage, memory, props,
                new MockEnvironment().withProperty("memory.enabled", "true")).tick();

        ArgumentCaptor<String> promotedSnippet = ArgumentCaptor.forClass(String.class);
        verify(memory).reinforceWithSnippet(eq("sid-secret"), eq("qhash:qhash-secret"),
                promotedSnippet.capture(), org.mockito.ArgumentMatchers.anyString(), anyDouble());
        assertFalse(promotedSnippet.getValue().contains(syntheticKey), promotedSnippet.getValue());
        assertFalse(promotedSnippet.getValue().contains("ownerToken=raw-token"), promotedSnippet.getValue());
        assertFalse(promotedSnippet.getValue().contains("api_key=" + syntheticKey), promotedSnippet.getValue());
    }

    private static final class SingleDrainStorage implements DegradedStorage {
        private final List<PendingMemoryEvent> events = new ArrayList<>();

        private SingleDrainStorage(PendingMemoryEvent event) {
            events.add(event);
        }

        @Override
        public void putPending(PendingMemoryEvent event) {
            events.add(event);
        }

        @Override
        public List<PendingMemoryEvent> drain(int max) {
            return List.copyOf(events);
        }
    }
}
