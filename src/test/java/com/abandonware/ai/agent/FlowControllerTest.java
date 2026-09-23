package com.abandonware.ai.agent;

import com.abandonware.ai.agent.context.ContextBridge;
import com.abandonware.ai.agent.orchestrator.Orchestrator;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolContextFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlowControllerTest {

    @Test
    void runStringifiesNonStringRoomIdForToolContext() {
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        FlowController controller = new FlowController(orchestrator, new ToolContextFactory(new ContextBridge()));

        Map<String, Object> response = controller.run("safe.v1", Map.of("roomId", 123), "on");

        assertThat(response).containsEntry("ok", true);
        assertThat(orchestrator.context.extras()).containsEntry("roomId", "123");
        assertThat(orchestrator.context.debugTrace()).isTrue();
    }

    private static final class CapturingOrchestrator extends Orchestrator {
        private ToolContext context;

        @Override
        public Map<String, Object> execute(String flowId, Map<String, Object> input, ToolContext ctx) {
            this.context = ctx;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("flowId", flowId);
            out.put("input", input);
            return out;
        }
    }
}
