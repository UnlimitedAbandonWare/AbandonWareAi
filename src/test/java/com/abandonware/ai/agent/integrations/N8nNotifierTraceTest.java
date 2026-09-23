package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class N8nNotifierTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void invalidWebhookUrlRecordsRedactedBreadcrumb() {
        String rawWebhook = "http://[::1/ownerToken/raw-secret-token";

        String host = N8nNotifier.webhookHost(rawWebhook);

        assertEquals("invalid-url", host);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.n8n.webhook.suppressed"));
        assertEquals("webhookHost.uri", TraceStore.get("agent.n8n.webhook.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("agent.n8n.webhook.suppressed.errorType"));
        assertEquals(rawWebhook.length(), TraceStore.get("agent.n8n.webhook.suppressed.webhookLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawWebhook));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret-token"));
    }

    @Test
    void webhookHashDigestFailureRecordsRedactedBreadcrumb() {
        String rawWebhook = "https://n8n.example.test/webhook/raw-secret-token";

        String hash = N8nNotifier.webhookHash(rawWebhook, "missing-digest");

        assertEquals("sha256-unavailable", hash);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.n8n.webhook.suppressed"));
        assertEquals("webhookHash.digest", TraceStore.get("agent.n8n.webhook.suppressed.stage"));
        assertEquals("NoSuchAlgorithmException", TraceStore.get("agent.n8n.webhook.suppressed.errorType"));
        assertEquals(rawWebhook.length(), TraceStore.get("agent.n8n.webhook.suppressed.webhookLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawWebhook));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret-token"));
    }
}
