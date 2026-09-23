package com.example.lms.api.internal;

import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.example.lms.security.AdminTokenGuardInterceptor;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAgentToolControllerSecurityTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.cleanupCurrentThread();
    }

    @Test
    void disabledApiReturnsNotFound() {
        InternalAgentToolController controller = controller(false, "admin-secret");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.tools(request(null)));

        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void enabledApiRequiresAdminOrOwnerToken() {
        InternalAgentToolController controller = controller(true, "admin-secret");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.tools(request(null)));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void validAdminTokenCanListTools() {
        InternalAgentToolController controller = controller(true, "admin-secret");

        Map<String, Object> body = controller.tools(request("admin-secret"));

        assertEquals(true, body.get("ok"));
    }

    @Test
    void validAdminTokenCanInvokeDbEvidenceScanTool() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.describeTools()).thenReturn(Map.of("ok", true, "tools", List.of()));
        when(invoker.invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true)))
                .thenReturn(Map.of("ok", true, "toolId", "db_evidence_scan"));
        InternalAgentToolController controller = controller(true, "admin-secret", invoker);

        Map<String, Object> body = controller.invokeTool(
                "db_evidence_scan", Map.of(), requestWithBudget("admin-secret", "250"));

        assertEquals(true, body.get("ok"));
        assertEquals("db_evidence_scan", body.get("toolId"));
        verify(invoker).invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true));
    }

    @Test
    void invokeTrimsSessionHeaderBeforeBuildingToolContext() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true)))
                .thenReturn(Map.of("ok", true));
        InternalAgentToolController controller = controller(true, "admin-secret", invoker);
        MockHttpServletRequest request = requestWithBudget("admin-secret", "250");
        request.addHeader("X-Session-Id", " session-1 ");

        controller.invokeTool("db_evidence_scan", Map.of(), request);

        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(invoker).invoke(eq("db_evidence_scan"), eq(Map.of()), contextCaptor.capture(), eq(true));
        assertEquals("session-1", contextCaptor.getValue().sessionId());
    }

    @Test
    void legacyStatelessToolKeepsBudgetHeaderCompatibility() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true)))
                .thenReturn(Map.of("ok", true));
        InternalAgentToolController controller = controller(true, "admin-secret", invoker);

        Map<String, Object> result = controller.invokeTool(
                "db_evidence_scan", Map.of(), request("admin-secret"));

        assertEquals(Boolean.TRUE, result.get("ok"));
        verify(invoker).invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true));
    }

    @Test
    void invokeAttachesBoundedOperatorBudgetBeforeCallingTool() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true)))
                .thenAnswer(ignored -> {
                    long remainingMs = TraceContext.current().remainingMillis();
                    assertTrue(remainingMs > 0L && remainingMs <= 250L, "remainingMs=" + remainingMs);
                    return Map.of("ok", true);
                });
        InternalAgentToolController controller = controller(true, "admin-secret", invoker);

        Map<String, Object> body = controller.invokeTool(
                "db_evidence_scan", Map.of(), requestWithBudget("admin-secret", "250"));

        assertEquals(true, body.get("ok"));
    }

    @Test
    void statefulPacketToolsRequireExplicitSessionHeader() {
        InternalAgentToolController controller = controller(true, "admin-secret");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.invokeTool(
                        "counter.evidence.retrieve",
                        Map.of(),
                        requestWithBudget("admin-secret", "250")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("agent_tool_session_required", ex.getReason());

        ResponseStatusException causal = assertThrows(
                ResponseStatusException.class,
                () -> controller.invokeTool(
                        "causal.probe.evaluate",
                        Map.of(),
                        requestWithBudget("admin-secret", "250")));
        assertEquals(400, causal.getStatusCode().value());
        assertEquals("agent_tool_session_required", causal.getReason());
    }

    @Test
    void whitespacePaddedStatefulToolIdCannotBypassSessionGate() {
        InternalAgentToolController controller = controller(true, "admin-secret");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.invokeTool(
                        " causal.probe.evaluate ",
                        Map.of(),
                        requestWithBudget("admin-secret", "250")));

        assertEquals(400, ex.getStatusCode().value());
        assertEquals("agent_tool_session_required", ex.getReason());
    }

    @Test
    void tighterOperatorHeaderClampsExistingRequestDeadline() {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.invoke(eq("db_evidence_scan"), eq(Map.of()), any(), eq(true)))
                .thenAnswer(ignored -> {
                    long remainingMs = TraceContext.current().remainingMillis();
                    assertTrue(remainingMs > 0L && remainingMs <= 250L, "remainingMs=" + remainingMs);
                    return Map.of("ok", true);
                });
        InternalAgentToolController controller = controller(true, "admin-secret", invoker);

        try (TraceContext ignored = TraceContext.attach("session", "existing-budget")
                .startWithBudget(java.time.Duration.ofSeconds(5))) {
            Map<String, Object> body = controller.invokeTool(
                    "db_evidence_scan", Map.of(), requestWithBudget("admin-secret", "250"));
            assertEquals(true, body.get("ok"));
        }
    }

    private static InternalAgentToolController controller(boolean enabled, String token) {
        AgentToolInvoker invoker = mock(AgentToolInvoker.class);
        when(invoker.describeTools()).thenReturn(Map.of("ok", true, "tools", java.util.List.of()));
        return controller(enabled, token, invoker);
    }

    private static InternalAgentToolController controller(boolean enabled, String token, AgentToolInvoker invoker) {
        AdminTokenGuardInterceptor guard = new AdminTokenGuardInterceptor();
        ReflectionTestUtils.setField(guard, "expectedToken", token);
        ReflectionTestUtils.setField(guard, "ownerToken", "");
        ReflectionTestUtils.setField(guard, "tokenRequired", true);
        ReflectionTestUtils.setField(guard, "activeProfiles", "local");
        InternalAgentToolController controller = new InternalAgentToolController(invoker, guard);
        ReflectionTestUtils.setField(controller, "apiEnabled", enabled);
        return controller;
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/agent/tools");
        request.setRequestURI("/internal/agent/tools");
        if (token != null) {
            request.addHeader(AdminTokenGuardInterceptor.HEADER, token);
        }
        return request;
    }

    private static MockHttpServletRequest requestWithBudget(String token, String budgetMs) {
        MockHttpServletRequest request = request(token);
        request.addHeader("X-Agent-Tool-Budget-Ms", budgetMs);
        return request;
    }
}
