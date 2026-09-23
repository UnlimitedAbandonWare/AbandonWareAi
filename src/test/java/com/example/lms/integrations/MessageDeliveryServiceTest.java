package com.example.lms.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessageDeliveryServiceTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void disabledResultPreservesSafePresenceDiagnosticsWithoutRawPayloads() {
        MessageDeliveryService service = new MessageDeliveryService();
        String targetId = "channel-user-secret-123";
        String text = "private message api_key=message-secret";
        String url = "https://example.test/upload?token=raw-url-token";

        MessageDeliveryResult result = service.sendUrl(targetId, text, url);

        assertNotNull(result);
        assertFalse(result.accepted());
        assertEquals("outbox/noop", result.provider());
        assertEquals(SafeRedactor.hash12(targetId), result.targetHash());
        assertEquals("message delivery provider is not configured", result.disabledReason());
        assertEquals(Boolean.TRUE, result.diagnostics().get("hasText"));
        assertEquals(Boolean.TRUE, result.diagnostics().get("hasUrl"));
        assertEquals(SafeRedactor.hash12(targetId), result.diagnostics().get("targetHash"));
        assertEquals(Boolean.TRUE, TraceStore.get("message.delivery.providerDisabled"));
        assertEquals("provider_disabled", TraceStore.get("message.delivery.skipped.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("message.delivery.hasText"));
        assertEquals(Boolean.TRUE, TraceStore.get("message.delivery.hasUrl"));

        String rendered = result.toString() + " " + TraceStore.getAll();
        assertFalse(rendered.contains(targetId));
        assertFalse(rendered.contains("message-secret"));
        assertFalse(rendered.contains("raw-url-token"));
        assertFalse(rendered.contains("api_key="));
    }
}
