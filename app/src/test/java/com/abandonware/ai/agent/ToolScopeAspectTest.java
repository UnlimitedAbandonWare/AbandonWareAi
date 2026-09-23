package com.abandonware.ai.agent;

import com.abandonware.ai.agent.consent.ConsentCardRenderer;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.ToolScopeAspectTest
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.consent.ConsentCardRenderer
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.ToolScopeAspectTest
role: config
flags: [sse]
*/
public class ToolScopeAspectTest {
    @Test
    void consentCardRendererLoads() {
        ConsentCardRenderer r = new ConsentCardRenderer();
        String json = r.renderBasic("templates/channel_consent_card.basic.json", "s", "r",
                java.util.List.of("message.send"), 3600);
        assertTrue(json.contains("basicCard"));
    }
}