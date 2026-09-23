package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.integrations.N8nNotifier;
import com.abandonware.ai.agent.tool.impl.N8nNotifyTool;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class N8nNotifyToolSecretSafetyTest {

    @Test
    void responseDoesNotExposeWebhookUrlOrPayloadSecrets() {
        N8nNotifyTool tool = new N8nNotifyTool(new N8nNotifier());
        String webhookUrl = "https://n8n.example.test/webhook/super-secret-token?ownerToken=raw-owner-token";
        Map<String, Object> payload = Map.of(
                "message", "hello",
                "apiKey", "" + com.example.lms.test.SecretFixtures.openAiKey() + "");

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("webhookUrl", webhookUrl, "payload", payload),
                new ToolContext("session-1", null, Map.of("requestId", "request-1"))));

        String rendered = response.data().toString();
        assertThat(response.data()).containsEntry("notified", true);
        assertThat(response.data()).doesNotContainKey("webhookUrl");
        assertThat(rendered)
                .doesNotContain(webhookUrl)
                .doesNotContain("super-secret-token")
                .doesNotContain("raw-owner-token")
                .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "");
        assertThat(response.data())
                .containsEntry("webhookConfigured", true)
                .containsEntry("webhookHost", "n8n.example.test")
                .containsKey("webhookHash")
                .containsEntry("payloadSize", 2);
    }

    @Test
    void acceptsNonStringWebhookUrlWithoutEchoingRawValue() {
        N8nNotifyTool tool = new N8nNotifyTool(new N8nNotifier());
        URI webhookUrl = URI.create("https://n8n.example.test/webhook/secret-path");

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("webhookUrl", webhookUrl, "payload", Map.of("ok", true)),
                new ToolContext("session-1", null)));

        String rendered = response.data().toString();
        assertThat(response.data())
                .containsEntry("notified", true)
                .containsEntry("webhookConfigured", true)
                .containsEntry("webhookHost", "n8n.example.test")
                .containsEntry("payloadSize", 1);
        assertThat(rendered)
                .doesNotContain("secret-path")
                .doesNotContain(webhookUrl.toString());
    }
}
