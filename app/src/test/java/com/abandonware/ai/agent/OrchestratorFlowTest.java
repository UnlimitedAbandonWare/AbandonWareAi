package com.abandonware.ai.agent;

import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.observability.AgentTracer;
import com.abandonware.ai.agent.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.OrchestratorFlowTest
 * Role: config
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.agent.consent.BasicConsentService, com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader, com.abandonware.ai.agent.orchestrator.Orchestrator, +2 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.OrchestratorFlowTest
role: config
flags: [sse]
*/
public class OrchestratorFlowTest {
    @Test
    void planCriticSynthExecuteWithoutTools() {
        Orchestrator orch = new Orchestrator(new ToolRegistry(), new BasicConsentService(),
                new FlowDefinitionLoader(), new AgentTracer(), new AgentMetrics());
        var ctx = new ToolContext("sess", Map.of());
        var out = orch.execute("kakao_ask", Map.of("text","테스트"), ctx);
        assertNotNull(out);
    }
}