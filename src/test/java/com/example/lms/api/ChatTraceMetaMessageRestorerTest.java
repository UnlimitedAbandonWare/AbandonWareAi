package com.example.lms.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTraceMetaMessageRestorerTest {

    @Test
    void snapshotPointerRestoresOnlySafeIdsWhenTraceIsExposed() {
        assertTrue(ChatTraceMetaMessageRestorer.restore(
                1L,
                "?TRACESNAP?snap_20260612-1433.01",
                LocalDateTime.of(2026, 6, 12, 14, 33),
                false).isEmpty());

        ChatApiController.MessageDto dto = ChatTraceMetaMessageRestorer.restore(
                2L,
                "?TRACESNAP?snap_20260612-1433.01",
                LocalDateTime.of(2026, 6, 12, 14, 34),
                true).orElseThrow();

        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("data-trace-snapshot-id=\"snap_20260612-1433.01\""));
        assertTrue(dto.content().contains("/api/diagnostics/trace/snapshots/snap_20260612-1433.01/html"));
        assertTrue(ChatTraceMetaMessageRestorer.restore(
                3L,
                "?TRACESNAP?../unsafe",
                LocalDateTime.now(),
                true).isEmpty());
    }

    @Test
    void legacyTracePayloadsRestoreAsRedactedSummaries() {
        String rawHtml = "<section>ownerToken=secret-value trace</section>";
        String encoded = Base64.getEncoder().encodeToString(rawHtml.getBytes(StandardCharsets.UTF_8));

        for (String content : java.util.List.of("?TRACE?" + rawHtml, "?TRACE64?" + encoded)) {
            ChatApiController.MessageDto dto = ChatTraceMetaMessageRestorer.restore(
                    4L,
                    content,
                    LocalDateTime.of(2026, 6, 12, 14, 35),
                    true).orElseThrow();

            assertEquals("system", dto.role());
            assertTrue(dto.content().contains("traceHtml"));
            assertFalse(dto.content().contains(rawHtml));
            assertFalse(dto.content().contains("secret-value"));
        }
    }

    @Test
    void malformedDurableEnvelopeFailsClosedWithoutEchoingPayload() {
        String privateSentinel = "ownerToken=PRIVATE_TRACE_56";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(privateSentinel.getBytes(StandardCharsets.UTF_8));

        java.util.Optional<ChatApiController.MessageDto> restored = ChatTraceMetaMessageRestorer.restore(
                5L,
                "?TRACESNAP?snap-safe|v1|" + encoded,
                LocalDateTime.of(2026, 8, 26, 12, 1),
                true);

        assertTrue(restored.isEmpty() || !restored.orElseThrow().content().contains(privateSentinel));
    }

    @Test
    void oversizedDurableEnvelopeFallsBackToPointerOnly() {
        String oversized = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("x".repeat(2_049).getBytes(StandardCharsets.UTF_8));

        ChatApiController.MessageDto restored = ChatTraceMetaMessageRestorer.restore(
                6L,
                "?TRACESNAP?snap-safe|v1|" + oversized,
                LocalDateTime.of(2026, 8, 26, 12, 2),
                true).orElseThrow();

        assertTrue(restored.content().contains("data-trace-snapshot-id=\"snap-safe\""));
        assertFalse(restored.content().contains("durable_fallback"));
        assertFalse(restored.content().contains(oversized));
    }
}
