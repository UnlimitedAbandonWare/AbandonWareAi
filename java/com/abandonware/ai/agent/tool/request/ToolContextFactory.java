package com.abandonware.ai.agent.tool.request;

import com.abandonware.ai.agent.consent.ConsentToken;
import com.abandonware.ai.agent.context.ContextBridge;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * Factory for creating {@link ToolContext} from the ambient request context.
 * Controllers can inject this factory and obtain a context without manually
 * wiring session/consent details.
 */
@Component
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