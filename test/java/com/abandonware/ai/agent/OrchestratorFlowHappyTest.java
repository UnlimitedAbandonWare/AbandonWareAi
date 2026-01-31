package com.abandonware.ai.agent;

import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.orchestrator.FlowDefinitionLoader;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.impl.*;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.observability.AgentTracer;
import com.abandonware.ai.agent.observability.AgentMetrics;
import org.junit.jupiter.api.Test;
import java.util.Map;




import static org.junit.jupiter.api.Assertions.*;

public class OrchestratorFlowHappyTest {

    @Test
    void fullFlowWithTools() {
        ToolRegistry reg = new ToolRegistry();
        // Register stub tools
        reg.register(new KakaoPushTool(new com.abandonware.ai.agent.integrations.KakaoMessageService()));
        reg.register(new N8nNotifyTool(new com.abandonware.ai.agent.integrations.N8nNotifier()));
        reg.register(new RagRetrieveTool(new com.abandonware.ai.agent.integrations.HybridRetriever()));
        reg.register(new WebSearchTool(new com.abandonware.ai.agent.integrations.TavilyWebSearchRetriever()));
        reg.register(new JobsEnqueueTool(new com.abandonware.ai.agent.job.DurableJobService(new com.abandonware.ai.agent.job.InMemoryJobQueue())));

        Orchestrator orch = new Orchestrator(reg, new BasicConsentService(),
                new FlowDefinitionLoader(), new AgentTracer(), new AgentMetrics());

        var ctx = new ToolContext("sess", Map.of("roomId","room-123"));
        var out = orch.execute("kakao_ask", Map.of("text","오늘 공지 보내줘"), ctx);

        assertNotNull(out);
        // Expect answer synthesized and kakao tool potentially invoked (stubbed) and n8n tool present in state
        assertTrue(out.containsKey("answer") || out.containsKey("kakao.push"));
    }
}