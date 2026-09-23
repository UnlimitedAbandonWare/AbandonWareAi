package com.abandonware.ai.agent.tool.request;

import com.abandonware.ai.agent.context.ContextBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolContextFactoryTest {
    private final ContextBridge bridge = new ContextBridge();

    @BeforeEach
    @AfterEach
    void clearAmbientContext() {
        bridge.clearCurrent();
    }

    @Test
    void fromCurrentUsesInternalSessionWhenNoAmbientContextExists() {
        ToolContext context = new ToolContextFactory(bridge).minimal();

        assertEquals("internal-agent", context.sessionId());
        assertEquals("internal-agent", context.consent().sessionId());
    }

    @Test
    void fromCurrentDefensivelyCopiesExtras() {
        Map<String, Object> extras = new HashMap<>();
        extras.put("requestId", "rid-1");

        ToolContext context = new ToolContextFactory(bridge).fromCurrent(extras);
        extras.put("requestId", "rid-2");

        assertEquals("rid-1", context.extras().get("requestId"));
        assertFalse(context.extras().isEmpty());
    }
}
