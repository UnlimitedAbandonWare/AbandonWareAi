package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.N8nNotifier;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * Posts a JSON payload to an n8n webhook.  Requires the {@code n8n.notify}
 * scope.  The shim simply logs the call via {@link N8nNotifier} and
 * acknowledges success.
 */
@Component
@RequiresScopes({ToolScope.N8N_NOTIFY})
public class N8nNotifyTool implements AgentTool {
    private final N8nNotifier notifier;

    public N8nNotifyTool(N8nNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public String id() {
        return "n8n.notify";
    }

    @Override
    public String description() {
        return "Send a JSON payload to an n8n webhook.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String webhookUrl = (String) input.get("webhookUrl");
        Object payloadObj = input.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = payloadObj instanceof Map<?, ?> ? (Map<String, Object>) payloadObj : null;
        notifier.notify(webhookUrl, payload);
        return ToolResponse.ok().put("notified", true).put("webhookUrl", webhookUrl);
    }
}