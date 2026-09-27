package com.example.lms.api;

import com.example.lms.agent.context.AgentPipelineHealthController;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatWorkflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Agent DB 컨텍스트 미러링의 3-상태 분리 회귀 방지 —
 * 컨트롤러 부재 하나로 "기능 해제"를 단정하던 결함을 막는다.
 * 대상 메서드는 package-private static이므로 reflection으로 호출한다.
 */
class ChatWorkflowAgentDbMirrorFocusedTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    private static void mirror(AgentPipelineHealthController controller,
                               boolean featureEnabled,
                               boolean providerPresent) throws Exception {
        Method m = ChatWorkflow.class.getDeclaredMethod(
                "mirrorAgentDbContextAvailabilityForAgentDebug",
                AgentPipelineHealthController.class, boolean.class, boolean.class);
        m.setAccessible(true);
        m.invoke(null, controller, featureEnabled, providerPresent);
    }

    @Test
    void disabledFlagOffLabelsDisabled() throws Exception {
        mirror(null, false, false);
        assertEquals("DISABLED",
                TraceStore.get("agent.dbContext.agentVisible.status"));
        assertEquals("agent_db_context_disabled",
                TraceStore.get("agent.dbContext.agentVisible.reason"));
    }

    @Test
    void enabledButProviderMissingLabelsProbeUnavailable() throws Exception {
        mirror(null, true, false);
        assertEquals("PROBE_UNAVAILABLE",
                TraceStore.get("agent.dbContext.agentVisible.status"));
        assertEquals("agent_db_context_provider_unavailable",
                TraceStore.get("agent.dbContext.agentVisible.reason"));
        assertEquals("inspect_agent_db_context_bean_wiring",
                TraceStore.get("agent.dbContext.agentVisible.nextAction"));
    }

    @Test
    void enabledWithProviderButNoControllerLabelsControllerUnavailable() throws Exception {
        mirror(null, true, true);
        assertEquals("PROBE_UNAVAILABLE",
                TraceStore.get("agent.dbContext.agentVisible.status"));
        assertEquals("agent_db_context_controller_unavailable",
                TraceStore.get("agent.dbContext.agentVisible.reason"));
    }

    @Test
    void presentControllerLabelsOk() throws Exception {
        mirror(mock(AgentPipelineHealthController.class), true, true);
        assertEquals("OK",
                TraceStore.get("agent.dbContext.agentVisible.status"));
        assertEquals("agent_pipeline_health_controller_present",
                TraceStore.get("agent.dbContext.agentVisible.reason"));
    }
}
