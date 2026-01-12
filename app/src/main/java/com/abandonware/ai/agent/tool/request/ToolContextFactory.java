package com.abandonware.ai.agent.tool.request;

import com.abandonware.ai.agent.consent.ConsentToken;
import com.abandonware.ai.agent.context.ContextBridge;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.request.ToolContextFactory
 * Role: config
 * Dependencies: com.abandonware.ai.agent.consent.ConsentToken, com.abandonware.ai.agent.context.ContextBridge
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.request.ToolContextFactory
role: config
*/
public class ToolContextFactory {
    private final ContextBridge bridge;

    public ToolContextFactory(ContextBridge bridge) {
        this.bridge = bridge;
    }

    public ToolContext fromCurrent(Map<String,Object> extras) {
        String session = bridge.sessionId();
        ConsentToken token = new ConsentToken(session);
        return new ToolContext(session, token, extras == null ? Map.of() : extras);
    }

    public ToolContext minimal() {
        return fromCurrent(Map.of());
    }
}