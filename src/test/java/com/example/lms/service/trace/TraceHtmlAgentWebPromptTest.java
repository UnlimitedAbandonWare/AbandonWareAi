package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The admitted agent web-search consumer (ChatApiController.agentPromptSearch)
 * publishes request-local state under agent.webSearch.prompt.* plus the upstream
 * reason under agent.acmeGateway.result.reason. The per-answer trace panel must
 * surface those keys so operators can tell SKIPPED / FAIL_SOFT / empty-OK apart
 * without exporting the raw snapshot.
 */
class TraceHtmlAgentWebPromptTest {
    @AfterEach void clear() { TraceStore.clear(); }

    @Test void agentWebPromptFailureStateIsRenderedInTracePanel() {
        var input = Map.<String, Object>of(
                "agent.webSearch.prompt.status", "FAIL_SOFT",
                "agent.webSearch.prompt.reasonCode", "web_search_failed",
                "agent.webSearch.prompt.returnedCount", 0,
                "agent.acmeGateway.result.reason", "all-providers-failed");
        String html = new TraceHtmlBuilder(null).buildSplitPanel(null, null, null, null, input);
        assertTrue(html.contains("agent.webSearch.prompt.status"));
        assertTrue(html.contains("agent.webSearch.prompt.reasonCode"));
        assertTrue(html.contains("agent.webSearch.prompt.returnedCount"));
        assertTrue(html.contains("agent.acmeGateway.result.reason"));
        assertTrue(html.contains("FAIL_SOFT"));
        assertTrue(html.contains("web_search_failed"));
        assertTrue(html.contains("all-providers-failed"));
    }

    @Test void agentWebPromptSkippedDisallowedIsRenderedInTracePanel() {
        var input = Map.<String, Object>of(
                "agent.webSearch.prompt.status", "SKIPPED",
                "agent.webSearch.prompt.reasonCode", "web_search_request_disallowed",
                "agent.webSearch.prompt.returnedCount", 0);
        String html = new TraceHtmlBuilder(null).buildSplitPanel(null, null, null, null, input);
        assertTrue(html.contains("agent.webSearch.prompt.status"));
        assertTrue(html.contains("SKIPPED"));
        assertTrue(html.contains("web_search_request_disallowed"));
    }

    @Test void unrelatedDiagnosticsDoNotCreateAnEmptyAgentWebGroup() {
        String html = new TraceHtmlBuilder(null).buildSplitPanel(null, null, null, null,
                Map.of("orch.bypass", false));
        assertFalse(html.contains("agent.webSearch.prompt.status"));
    }
}
