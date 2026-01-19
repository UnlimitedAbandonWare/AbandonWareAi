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