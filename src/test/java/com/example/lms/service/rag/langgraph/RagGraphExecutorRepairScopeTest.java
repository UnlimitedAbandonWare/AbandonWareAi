package com.example.lms.service.rag.langgraph;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.auth.DomainWhitelist;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repair stage must preserve the original request scope (web-disabled,
 * whitelist-only, bounded topK/budget) and re-validate repaired evidence
 * (dedup, policy filter, capacity, post-merge quality) instead of appending
 * unverified docs.
 */
class RagGraphExecutorRepairScopeTest {

    @AfterEach
    void tearDown() {
        TimeBudgetContext.clear();
        TraceStore.clear();
        MDC.clear();
    }

    @Test
    void webDisabledRequestSkipsRepairWithoutCallingHandler() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        CapturingRepairHandler handler = new CapturingRepairHandler(List.of(Content.from("repaired")));
        RagGraphExecutor executor = new RagGraphExecutor(
                new EmptyResultsOrchestrator(), new FixedProvider<>(handler), properties);
        QueryRequest request = new QueryRequest();
        request.query = "needs repair";
        request.useWeb = false;

        QueryResponse response = executor.execute(request);

        assertEquals(0, handler.calls.get(),
                "web-disabled request must never reach the web repair handler");
        assertEquals("skipped_web_disabled", response.debug.get("langgraph.node.repair"));
        Object failureReason = response.debug.get("langgraph.failureReason");
        Object failSoftReason = response.debug.get("langgraph.failSoft.reasonCode");
        assertTrue("repair_web_disabled".equals(failureReason) || "repair_web_disabled".equals(failSoftReason),
                "repair skip reason must be visible in the failure/fail-soft trace");
    }

    @Test
    void repairQueryCarriesRequestScopeAndBoundedTopK() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        CapturingRepairHandler handler = new CapturingRepairHandler(
                List.of(Content.from("fresh repair evidence")));
        RagGraphExecutor executor = new RagGraphExecutor(
                new EmptyResultsOrchestrator(), new FixedProvider<>(handler), properties);
        QueryRequest request = new QueryRequest();
        request.query = "needs repair";
        request.useWeb = true;
        request.topK = 4;

        QueryResponse response = executor.execute(request);

        assertEquals(1, handler.calls.get());
        Query captured = handler.lastQuery.get();
        Map<String, Object> meta = QueryUtils.metadata(captured);
        assertEquals(Boolean.TRUE, meta.get("allowWeb"),
                "allowWeb must be explicit so web-enabled repairs remain deliberate");
        assertEquals(4, meta.get("webTopK"), "repair topK is bounded by request.topK");
        assertEquals("KB,MEMORY,LEGACY", meta.get(VectorMetaKeys.META_ALLOWED_DOC_TYPES));
        assertEquals("bounded", meta.get("rag.repair.scope"));
        assertEquals("ok", response.debug.get("langgraph.node.repair"));
        assertEquals(1, response.debug.get("langgraph.repair.added"));
        assertEquals(true, response.debug.get("langgraph.repair.reverified"));
    }

    @Test
    void repairedDocsAreDeduplicatedAndRankedBelowVerifiedEvidence() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        Content duplicate = Content.from(TextSegment.from(
                "existing evidence", Metadata.from(Map.of("url", "https://ex.com/a"))));
        CapturingRepairHandler handler = new CapturingRepairHandler(List.of(
                duplicate,
                Content.from("unique repair evidence")));
        RagGraphExecutor executor = new RagGraphExecutor(
                new WeakEvidenceOrchestrator(), new FixedProvider<>(handler), properties);
        QueryRequest request = new QueryRequest();
        request.query = "weak evidence repair";

        QueryResponse response = executor.execute(request);

        assertEquals("ok", response.debug.get("langgraph.node.repair"));
        assertEquals(1, response.debug.get("langgraph.repair.duplicateDropped"));
        assertEquals(1, response.debug.get("langgraph.repair.added"));
        assertEquals(3, response.results.size());
        assertEquals("existing evidence", response.results.get(0).snippet);
        Doc repaired = response.results.get(2);
        assertEquals("unique repair evidence", repaired.snippet);
        assertTrue(response.results.get(0).score >= repaired.score,
                "repaired evidence must not outrank previously verified results");
        assertEquals(true, repaired.meta.get("repair.unverified"));
        assertEquals(1, response.results.get(0).rank);
        assertEquals(3, repaired.rank);
    }

    @Test
    void whitelistOnlyDropsUnverifiableRepairUrls() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("official.gov"));
        CapturingRepairHandler handler = new CapturingRepairHandler(List.of(
                Content.from(TextSegment.from("blocked blog evidence",
                        Metadata.from(Map.of("url", "https://blog.example.com/x")))),
                Content.from("no-url repair evidence"),
                Content.from(TextSegment.from("official repair evidence",
                        Metadata.from(Map.of("url", "https://data.official.gov/doc"))))));
        RagGraphExecutor executor = new RagGraphExecutor(
                new WeakEvidenceOrchestrator(), new FixedProvider<>(handler), properties);
        ReflectionTestUtils.setField(executor, "domainWhitelist", whitelist);
        QueryRequest request = new QueryRequest();
        request.query = "whitelist repair";
        request.whitelistOnly = true;

        QueryResponse response = executor.execute(request);

        assertEquals("ok", response.debug.get("langgraph.node.repair"));
        assertEquals(2, response.debug.get("langgraph.repair.scopeDropped"));
        assertEquals(1, response.debug.get("langgraph.repair.added"));
        assertTrue(response.results.stream().anyMatch(d -> "official repair evidence".equals(d.snippet)));
        assertTrue(response.results.stream().noneMatch(d -> "blocked blog evidence".equals(d.snippet)));
        assertTrue(response.results.stream().noneMatch(d -> "no-url repair evidence".equals(d.snippet)));
        Query captured = handler.lastQuery.get();
        assertEquals(Boolean.TRUE, QueryUtils.metadata(captured).get("officialSourcesOnly"));
    }

    private static final class EmptyResultsOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = new QueryResponse();
            response.requestId = "empty";
            response.planApplied = req == null ? null : req.planId;
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class WeakEvidenceOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = new QueryResponse();
            response.requestId = "weak";
            response.planApplied = req == null ? null : req.planId;
            response.debug = new LinkedHashMap<>();
            Doc doc = new Doc();
            doc.id = "weak-doc";
            doc.title = "existing";
            doc.snippet = "existing evidence";
            doc.source = "VECTOR";
            doc.score = 0.8d;
            doc.rank = 1;
            doc.meta = new LinkedHashMap<>();
            doc.meta.put(VectorMetaKeys.META_SOURCE_URL, "https://ex.com/a");
            response.results.add(doc);
            Doc kg = new Doc();
            kg.id = "weak-kg";
            kg.title = "kg";
            kg.snippet = "kg evidence";
            kg.source = "KG";
            kg.score = 0.7d;
            kg.rank = 2;
            kg.meta = new LinkedHashMap<>();
            response.results.add(kg);
            response.debug.put("rag.eval.scorecard", Map.of(
                    "reasonCode", "kg_final_drop",
                    "thresholdLabels", List.of("kg_final_drop")));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class CapturingRepairHandler extends EvidenceRepairHandler {
        private final List<Content> repaired;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Query> lastQuery = new AtomicReference<>();

        private CapturingRepairHandler(List<Content> repaired) {
            super(null, null, "", "");
            this.repaired = repaired;
        }

        @Override
        public List<Content> retrieve(Query query) {
            calls.incrementAndGet();
            lastQuery.set(query);
            return repaired;
        }
    }

    private static final class FixedProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }
    }
}
