package com.example.lms.llm;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmGatewayHeadersTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void ownerTokenHeaderUsesConfiguredSafeHeaderName() {
        Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(
                "X-Mac-Mini-Owner",
                "owner-proxy-secret-value");

        assertEquals(Map.of("X-Mac-Mini-Owner", "owner-proxy-secret-value"), headers);
    }

    @Test
    void ownerTokenHeaderSkipsBlankAndPlaceholderValues() {
        for (String value : List.of("", "dummy", "sk-local", "test", "changeme", "change_me",
                "CHANGE_ME_LONG_RANDOM_TOKEN", "${LLM_OWNER_TOKEN:}", "placeholder",
                "<YOUR_API_KEY>", "<your-owner-token>", "todo", "tbd", "change-me")) {
            assertTrue(LocalLlmGatewaySecurity.ownerTokenHeaders("X-Owner-Token", value).isEmpty(), value);
            assertFalse(LocalLlmGatewaySecurity.hasUsableRemoteSecret(value), value);
        }
    }

    @Test
    void unsafeHeaderNamesFallBackToDefaultOwnerTokenHeader() {
        Map<String, String> headers = LocalLlmGatewaySecurity.ownerTokenHeaders(
                "Authorization",
                "owner-proxy-secret-value");

        assertEquals(Map.of("X-Owner-Token", "owner-proxy-secret-value"), headers);
    }

    @Test
    void ownerTokenIsOnlyAttachedToLocalOrPrivateGatewayHosts() {
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://api.openai.com/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://api.groq.com/openai/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://api.cerebras.ai/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://generativelanguage.googleapis.com/v1beta/openai"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://api.mistral.ai/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://api.openrouter.ai/api/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://opencode.ai/zen/v1"));
        assertTrue(LocalLlmGatewaySecurity.shouldAttachOwnerToken("http://localhost:11434/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken("https://macmini-ollama.internal/v1"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://macmini-ollama.internal/v1",
                ""));
        assertTrue(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://macmini-ollama.internal/v1",
                "macmini-ollama.internal"));
        assertTrue(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://192.168.1.40:11434/v1",
                "192.168.1.40:11434"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://api.openrouter.ai/api/v1",
                "api.openrouter.ai"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "generativelanguage.googleapis.com"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://api.mistral.ai/v1",
                "api.mistral.ai"));
        assertFalse(LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                "https://opencode.ai/zen/v1",
                "opencode.ai"));
    }

    @Test
    void invalidGatewayUrlLeavesRedactedTraceBreadcrumb() {
        String rawUrl = "http://bad host/private-owner-token";

        assertEquals("", LocalLlmGatewaySecurity.endpointHost(rawUrl));

        assertEquals(true, TraceStore.get("llm.localGateway.suppressed.parseUri"));
        assertEquals("invalid_url",
                TraceStore.get("llm.localGateway.suppressed.parseUri.errorType"));
        assertEquals("parseUri", TraceStore.get("llm.localGateway.suppressed.stage"));
        assertEquals("invalid_url", TraceStore.get("llm.localGateway.suppressed.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawUrl));
    }
}
