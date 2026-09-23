package com.abandonware.ai.agent.web;

import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTraceControllerRedactionTest {

    @Test
    void publishRedactsSensitiveTraceEventFieldsBeforeStreaming() {
        AgentTraceController controller = new AgentTraceController();
        var next = controller.stream().next().mapNotNull(event -> event.data()).cache();

        controller.publish(Map.of(
                "sessionId", "session-secret-123",
                "apiKey", "" + com.example.lms.test.SecretFixtures.openAiKey() + "",
                "query", "raw private prompt text",
                "provider", "naver"));

        Map<String, Object> event = next.block();
        String rendered = String.valueOf(event);

        assertEquals(SafeRedactor.hashValue("session-secret-123"), event.get("sessionId"));
        assertEquals("(redacted)", event.get("apiKey"));
        assertEquals("naver", event.get("provider"));
        assertFalse(rendered.contains("session-secret-123"));
        assertFalse(rendered.contains("" + com.example.lms.test.SecretFixtures.openAiKey() + ""));
        assertFalse(rendered.contains("raw private prompt text"));
    }
}
