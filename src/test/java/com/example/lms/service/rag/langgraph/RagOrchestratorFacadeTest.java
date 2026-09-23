package com.example.lms.service.rag.langgraph;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static com.example.lms.service.rag.langgraph.RagGraphExecutorTest.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagOrchestratorFacadeTest {

    @Test
    void langGraphOffUsesLegacyOnly() {
        RagGraphProperties properties = new RagGraphProperties();
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(response("graph", request()));
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);

        QueryResponse response = facade.query(request());

        assertEquals("legacy", response.requestId);
        assertEquals(1, legacy.queryCalls);
        assertEquals(0, graph.calls);
        assertEquals("off", response.debug.get("langgraph.mode"));
    }

    @Test
    void langGraphShadowReturnsLegacyAndRecordsGraphComparison() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.SHADOW);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        QueryResponse graphResponse = response("graph", request());
        graphResponse.debug.put("langgraph.node.repair", "ok");
        graphResponse.debug.put("langgraph.repair.added", 2);
        graphResponse.debug.put("langgraph.emptyReason", "repair_created_response");
        graphResponse.debug.put("langgraph.failureReason", "empty_results");
        graphResponse.debug.put("langgraph.checkpoint.backend", "memory");
        graphResponse.debug.put("langgraph.checkpoint.enabled", true);
        graphResponse.debug.put("langgraph.control.mode", "RECOVERY");
        graphResponse.debug.put("langgraph.control.reasonCode", "repair_ok");
        graphResponse.debug.put("langgraph.control.failureAction", "FINALIZE");
        graphResponse.debug.put("langgraph.transitionTrace", java.util.List.of(
                java.util.Map.of("node", "repair", "action", "FINALIZE", "reasonCode", "repair_ok")));
        StubGraphExecutor graph = new StubGraphExecutor(graphResponse);
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);

        QueryResponse response = facade.query(request());

        assertEquals("legacy", response.requestId);
        assertEquals(1, legacy.queryCalls);
        assertEquals(1, graph.calls);
        assertEquals("shadow", response.debug.get("langgraph.mode"));
        assertEquals(1, response.debug.get("langgraph.shadow.legacyResultCount"));
        assertEquals(1, response.debug.get("langgraph.shadow.graphResultCount"));
        assertTrue(((Number) response.debug.get("langgraph.shadow.latencyMsLegacy")).longValue() >= 0L);
        assertTrue(((Number) response.debug.get("langgraph.shadow.latencyMsGraph")).longValue() >= 0L);
        assertEquals(true, response.debug.get("langgraph.shadow.repairTriggered"));
        assertEquals(false, response.debug.get("langgraph.shadow.fallbackTriggered"));
        assertEquals(1.0d, (Double) response.debug.get("langgraph.shadow.answerDiffScore"), 0.0001d);
        assertEquals(1, response.debug.get("langgraph.shadow.resultCount"));
        assertEquals(0, response.debug.get("langgraph.shadow.deltaResultCount"));
        assertEquals("graph", response.debug.get("langgraph.shadow.requestId"));
        assertEquals("ok", response.debug.get("langgraph.shadow.repairStatus"));
        assertEquals(2, response.debug.get("langgraph.shadow.repairAdded"));
        assertEquals("repair_created_response", response.debug.get("langgraph.shadow.emptyReason"));
        assertEquals("empty_results", response.debug.get("langgraph.shadow.failureReason"));
        assertEquals("memory", response.debug.get("langgraph.shadow.checkpoint.backend"));
        assertEquals(true, response.debug.get("langgraph.shadow.checkpoint.enabled"));
        assertEquals("RECOVERY", response.debug.get("langgraph.shadow.control.mode"));
        assertEquals("repair_ok", response.debug.get("langgraph.shadow.control.reasonCode"));
        assertEquals("FINALIZE", response.debug.get("langgraph.shadow.control.failureAction"));
        assertTrue(response.debug.containsKey("langgraph.shadow.transitionTrace"));
        assertTrue(((Number) response.debug.get("langgraph.shadow.tookMs")).longValue() >= 0L);
        assertEquals(false, response.debug.get("langgraph.shadow.fallbackTriggered"));
        assertEquals(false, response.debug.get("langgraph.shadow.promotionEligible"));
        assertTrue(response.debug.containsKey("langgraph.shadow.promotionScore"));
        assertTrue(response.debug.get("langgraph.shadow.promotionBlockers").toString().contains("high_answer_diff"));
    }

    @Test
    void langGraphShadowReturnsLegacyAndRecordsGraphException() {
        TraceStore.clear();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.SHADOW);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(null);
        graph.failure = new IllegalStateException("boom");
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        QueryRequest request = request("private langgraph facade shadow query");

        QueryResponse response = facade.query(request);

        assertEquals("legacy", response.requestId);
        assertEquals(1, legacy.queryCalls);
        assertEquals(1, graph.calls);
        assertEquals("shadow", response.debug.get("langgraph.mode"));
        assertEquals(1, response.debug.get("langgraph.shadow.legacyResultCount"));
        assertEquals(0, response.debug.get("langgraph.shadow.graphResultCount"));
        assertTrue(((Number) response.debug.get("langgraph.shadow.latencyMsLegacy")).longValue() >= 0L);
        assertTrue(((Number) response.debug.get("langgraph.shadow.latencyMsGraph")).longValue() >= 0L);
        assertEquals(false, response.debug.get("langgraph.shadow.repairTriggered"));
        assertEquals(true, response.debug.get("langgraph.shadow.fallbackTriggered"));
        assertEquals(1.0d, (Double) response.debug.get("langgraph.shadow.answerDiffScore"), 0.0001d);
        assertEquals(0, response.debug.get("langgraph.shadow.resultCount"));
        assertEquals(-1, response.debug.get("langgraph.shadow.deltaResultCount"));
        assertEquals("IllegalStateException", response.debug.get("langgraph.shadow.error"));
        assertTrue(((Number) response.debug.get("langgraph.shadow.tookMs")).longValue() >= 0L);
        assertEquals(false, response.debug.get("langgraph.shadow.promotionEligible"));
        assertEquals(0.0d, (Double) response.debug.get("langgraph.shadow.promotionScore"), 0.0001d);
        assertTrue(response.debug.get("langgraph.shadow.promotionBlockers").toString().contains("graph_exception"));
        assertEquals(true, TraceStore.get("langgraph.shadow.fallbackTriggered"));
        assertEquals("graph_exception", TraceStore.get("langgraph.shadow.failureClass"));
        assertEquals("IllegalStateException", TraceStore.get("langgraph.shadow.errorType"));
        assertTrue(String.valueOf(TraceStore.get("langgraph.shadow.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(request.query.length(), TraceStore.get("langgraph.shadow.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(request.query));
        TraceStore.clear();
    }

    @Test
    void langGraphShadowCancellationUsesOperationalFailureClass() {
        TraceStore.clear();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.SHADOW);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(null);
        graph.failure = new CancellationException("cancelled ownerToken fake-token");
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        QueryRequest request = request("private facade shadow cancellation");

        try {
            QueryResponse response = facade.query(request);

            assertEquals("legacy", response.requestId);
            assertEquals("cancelled", response.debug.get("langgraph.shadow.error"));
            assertEquals("cancelled", TraceStore.get("langgraph.shadow.errorType"));
            String rendered = String.valueOf(response.debug) + TraceStore.getAll();
            assertFalse(rendered.contains("CancellationException"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains(request.query));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void langGraphPrimaryReturnsGraphWhenHealthy() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(response("graph", request()));
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);

        QueryResponse response = facade.query(request());

        assertEquals("graph", response.requestId);
        assertEquals(0, legacy.queryCalls);
        assertEquals(1, graph.calls);
        assertEquals("primary", response.debug.get("langgraph.mode"));
        assertEquals(true, response.debug.get("langgraph.primary.promotionEligible"));
        assertTrue(((Number) response.debug.get("langgraph.primary.promotionScore")).doubleValue() >= 0.70d);
    }

    @Test
    void langGraphPrimaryMarksSequentialInvokeFallbackAsDegraded() {
        TraceStore.clear();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        QueryResponse graphResponse = response("graph", request());
        graphResponse.debug.put("langgraph.fallback", "sequential");
        graphResponse.debug.put("langgraph.invoke.fallbackTriggered", true);
        graphResponse.debug.put("langgraph.invoke.trigger", "graph_invoke_npe");
        graphResponse.debug.put("langgraph.invoke.failureClass", "langgraph-invoke-null");
        graphResponse.debug.put("langgraph.control.transitionScore", Double.NaN);
        graphResponse.debug.put("langgraph.control.promotionBlockers", java.util.List.of("invoke_fallback"));
        StubGraphExecutor graph = new StubGraphExecutor(graphResponse);
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);

        try {
            QueryResponse response = facade.query(request());

            assertEquals("graph", response.requestId);
            assertEquals(0, legacy.queryCalls);
            assertEquals(1, graph.calls);
            assertEquals("primary-degraded", response.debug.get("langgraph.mode"));
            assertEquals("compiled_graph_invoke", response.debug.get("langgraph.primary.fallback"));
            assertEquals(false, response.debug.get("langgraph.primary.promotionEligible"));
            assertTrue(response.debug.get("langgraph.primary.promotionBlockers").toString().contains("invoke_fallback"));
            assertEquals(true, TraceStore.get("langgraph.facade.suppressed.asDouble"));
            assertEquals("invalid_number", TraceStore.get("langgraph.facade.suppressed.asDouble.errorType"));
            assertEquals("asDouble", TraceStore.get("langgraph.facade.suppressed.stage"));
            assertEquals("invalid_number", TraceStore.get("langgraph.facade.suppressed.errorType"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void langGraphPrimaryFallsBackToLegacyOnGraphException() {
        TraceStore.clear();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(null);
        graph.failure = new IllegalStateException("boom");
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        QueryRequest request = request("private langgraph facade primary query");

        QueryResponse response = facade.query(request);

        assertEquals("legacy", response.requestId);
        assertEquals(1, legacy.queryCalls);
        assertEquals(1, graph.calls);
        assertEquals("primary-fallback", response.debug.get("langgraph.mode"));
        assertEquals("IllegalStateException", response.debug.get("langgraph.primary.fallback"));
        assertEquals(false, response.debug.get("langgraph.primary.promotionEligible"));
        assertTrue(response.debug.get("langgraph.primary.promotionBlockers").toString().contains("graph_exception"));
        assertEquals(true, TraceStore.get("langgraph.primary.fallbackTriggered"));
        assertEquals("graph_exception", TraceStore.get("langgraph.primary.failureClass"));
        assertEquals("IllegalStateException", TraceStore.get("langgraph.primary.errorType"));
        assertTrue(String.valueOf(TraceStore.get("langgraph.primary.queryHash12")).matches("[0-9a-f]{12}"));
        assertEquals(request.query.length(), TraceStore.get("langgraph.primary.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(request.query));
        TraceStore.clear();
    }

    @Test
    void langGraphPrimaryCancellationFallbackUsesOperationalFailureClass() {
        TraceStore.clear();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setMode(RagGraphProperties.Mode.PRIMARY);
        FakeOrchestrator legacy = new FakeOrchestrator("legacy");
        StubGraphExecutor graph = new StubGraphExecutor(null);
        graph.failure = new CancellationException("cancelled ownerToken fake-token");
        RagOrchestratorFacade facade = new RagOrchestratorFacade(legacy, graph, properties);
        QueryRequest request = request("private facade primary cancellation");

        try {
            QueryResponse response = facade.query(request);

            assertEquals("legacy", response.requestId);
            assertEquals("primary-fallback", response.debug.get("langgraph.mode"));
            assertEquals("cancelled", response.debug.get("langgraph.primary.fallback"));
            assertEquals("cancelled", TraceStore.get("langgraph.primary.errorType"));
            String rendered = String.valueOf(response.debug) + TraceStore.getAll();
            assertFalse(rendered.contains("CancellationException"));
            assertFalse(rendered.contains("ownerToken"));
            assertFalse(rendered.contains(request.query));
        } finally {
            TraceStore.clear();
        }
    }

    private static QueryRequest request() {
        return request("langgraph facade");
    }

    private static QueryRequest request(String query) {
        QueryRequest request = new QueryRequest();
        request.query = query;
        request.planId = "safe_autorun.v1";
        return request;
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private final String id;
        private int queryCalls;

        private FakeOrchestrator(String id) {
            this.id = id;
        }

        @Override
        public QueryResponse query(QueryRequest req) {
            queryCalls++;
            return response(id, req);
        }
    }

    private static final class StubGraphExecutor extends RagGraphExecutor {
        private final QueryResponse response;
        private RuntimeException failure;
        private int calls;

        private StubGraphExecutor(QueryResponse response) {
            super(new UnifiedRagOrchestrator(), new RagGraphExecutorTest.FixedProvider<>(null), new RagGraphProperties());
            this.response = response;
        }

        @Override
        public QueryResponse execute(QueryRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
