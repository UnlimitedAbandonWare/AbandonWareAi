package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagRetrieveToolTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void malformedSessionIdLeavesTypeOnlyTraceAndStillRetrieves() {
        HybridRetriever retriever = mock(HybridRetriever.class);
        List<Map<String, Object>> results = List.of(Map.of("id", "doc-1"));
        when(retriever.retrieve(eq("q"), isNull(), isNull())).thenReturn(results);
        RagRetrieveTool tool = new RagRetrieveTool(retriever);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("query", "q", "sessionId", "raw private session token"),
                new ToolContext("ctx", null)));

        assertEquals(results, response.data().get("results"));
        assertEquals(1L, TraceStore.get("agent.tool.ragRetrieve.sessionId.invalid.count"));
        assertEquals("sessionId", TraceStore.get("agent.tool.ragRetrieve.sessionId.invalid.stage"));
        assertEquals("invalid_number", TraceStore.get("agent.tool.ragRetrieve.sessionId.invalid.errorType"));
        assertTrueHash("agent.tool.ragRetrieve.sessionId.invalid.valueHash");
        assertEquals("raw private session token".length(),
                TraceStore.get("agent.tool.ragRetrieve.sessionId.invalid.valueLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private session token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievalLeavesHashCountAndOverdriveTraceWithoutRawQuery() {
        HybridRetriever retriever = mock(HybridRetriever.class);
        OverdriveGuard overdriveGuard = mock(OverdriveGuard.class);
        ObjectProvider<OverdriveGuard> provider = mock(ObjectProvider.class);
        List<Map<String, Object>> results = List.of(Map.of(
                "title", "doc",
                "snippet", "safe evidence"));
        when(retriever.retrieve(eq("private query sk-" + "redactioncontract1234567890"), eq(3), eq("local")))
                .thenReturn(results);
        when(provider.getIfAvailable()).thenReturn(overdriveGuard);
        when(overdriveGuard.shouldActivate(eq("private query sk-" + "redactioncontract1234567890"), anyList()))
                .thenReturn(true);
        RagRetrieveTool tool = new RagRetrieveTool(retriever, provider);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("query", "private query sk-" + "redactioncontract1234567890", "topK", 3, "domain", "local"),
                new ToolContext("ctx", null)));

        assertEquals(results, response.data().get("results"));
        assertEquals(3, TraceStore.get("tool.rag.retrieve.requestedK"));
        assertEquals(1, TraceStore.get("tool.rag.retrieve.returnedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.rag.retrieve.overdriveTriggered"));
        assertEquals(Boolean.FALSE, TraceStore.get("tool.rag.retrieve.zeroResults"));
        assertTrueHash("tool.rag.retrieve.queryHash");
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("sk-redactioncontract"));
    }

    @Test
    void retrievalAcceptsStringTopKAndNormalizesDomain() {
        HybridRetriever retriever = mock(HybridRetriever.class);
        List<Map<String, Object>> results = List.of(Map.of("id", "doc-1"));
        when(retriever.retrieve(eq("q"), eq(4), eq("local"))).thenReturn(results);
        RagRetrieveTool tool = new RagRetrieveTool(retriever);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("query", " q ", "topK", "4", "domain", " local "),
                new ToolContext("ctx", null)));

        assertEquals(results, response.data().get("results"));
        assertEquals(4, TraceStore.get("tool.rag.retrieve.requestedK"));
        assertEquals(1, TraceStore.get("tool.rag.retrieve.returnedCount"));
    }

    @Test
    void invalidTopKLeavesRedactedBreadcrumbAndUsesFallback() {
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(eq("q"), isNull(), isNull())).thenReturn(List.of());
        RagRetrieveTool tool = new RagRetrieveTool(retriever);

        tool.execute(new ToolRequest(
                Map.of("query", "q", "topK", "private topk"),
                new ToolContext("ctx", null)));

        assertEquals(6, TraceStore.get("tool.rag.retrieve.requestedK"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.rag.retrieve.suppressed"));
        assertEquals("topK", TraceStore.get("tool.rag.retrieve.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("tool.rag.retrieve.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private topk"));
    }

    @Test
    void emptyRetrievalLeavesReasonAndNoOverdriveFailure() {
        HybridRetriever retriever = mock(HybridRetriever.class);
        when(retriever.retrieve(eq("q"), eq(2), isNull())).thenReturn(List.of());
        RagRetrieveTool tool = new RagRetrieveTool(retriever);

        tool.execute(new ToolRequest(Map.of("query", "q", "topK", 2), new ToolContext("ctx", null)));

        assertEquals(0, TraceStore.get("tool.rag.retrieve.returnedCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("tool.rag.retrieve.overdriveTriggered"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.rag.retrieve.zeroResults"));
        assertEquals("hybridRetriever_returned_empty", TraceStore.get("tool.rag.retrieve.emptyReason"));
    }

    private static void assertTrueHash(String key) {
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(TraceStore.get(key)).startsWith("hash:"));
    }
}
