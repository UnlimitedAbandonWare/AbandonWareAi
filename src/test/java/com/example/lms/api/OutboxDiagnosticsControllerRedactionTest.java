package com.example.lms.api;

import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import ai.abandonware.nova.orch.storage.PendingMemoryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxDiagnosticsControllerRedactionTest {

    @Test
    void statsAndPeekExposeDiagnosticSummariesOnly() {
        DegradedStorageWithAck storage = mock(DegradedStorageWithAck.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ai.abandonware.nova.orch.storage.DegradedStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);

        String rawPath = "C:/private/outbox/session-secret/path";
        String rawSession = "session-secret-raw";
        String rawContext = "context-owner-token";
        String rawSnippet = "private answer snippet api_key=snippet-secret";
        String rawError = "failed with Authorization=Bearer " + "secret-token";
        when(storage.stats()).thenReturn(new DegradedStorageWithAck.OutboxStats(
                true, "jsonl", rawPath, 1, 0, 123,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                100, 1_000_000, 3600, 60, 1, 2, 3, 4, 5, 6, 7));
        when(storage.peek("pending", 1, 32)).thenReturn(List.of(new DegradedStorageWithAck.OutboxPeekItem(
                "outbox-token-secret",
                "pending",
                2,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                123,
                rawError,
                new PendingMemoryEvent(rawSession, rawContext, "raw-query-hash-maybe-secret",
                        rawSnippet, Instant.parse("2026-01-01T00:00:02Z"), 99, "ownerToken=reason-secret"),
                Map.of("file", rawPath, "payload", rawSnippet))));

        OutboxDiagnosticsController controller = new OutboxDiagnosticsController(provider);

        String stats = String.valueOf(controller.stats().getBody());
        String peek = String.valueOf(controller.peek("pending", 1, 32).getBody());
        String combined = stats + "\n" + peek;

        assertTrue(stats.contains("pathHash"));
        assertTrue(peek.contains("answerSnippetHash"));
        assertTrue(peek.contains("lastErrorHash"));
        assertFalse(combined.contains(rawPath));
        assertFalse(combined.contains(rawSession));
        assertFalse(combined.contains(rawContext));
        assertFalse(combined.contains(rawSnippet));
        assertFalse(combined.contains("snippet-secret"));
        assertFalse(combined.contains("secret-token"));
        assertFalse(combined.contains("reason-secret"));
        assertFalse(combined.contains("outbox-token-secret"));
    }
}
