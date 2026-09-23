package com.example.lms.ops;

import com.example.lms.entity.RagOpsLedgerEntry;
import com.example.lms.repository.RagOpsLedgerRepository;
import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.example.lms.repository.VectorShadowMergeDlqRepository;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ops.RagOpsLedgerService;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker.CycleDiagnostics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagOpsLedgerServiceTest {

    @Mock
    private RagOpsLedgerRepository repository;

    private RagOpsLedgerService service;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        service = new RagOpsLedgerService(
                repository,
                new ObjectMapper(),
                emptyProvider(),
                emptyProvider());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "captureRag", true);
        ReflectionTestUtils.setField(service, "captureAutolearn", true);
        ReflectionTestUtils.setField(service, "maxJsonChars", 4000);
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void disabledLedgerDoesNotChangeRagFlowOrWrite() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.recordRagRun(new QueryRequest(), new QueryResponse(), 12L);

        verifyNoInteractions(repository);
    }

    @Test
    void ragRunStoresHashesCountsAndRedactedJsonOnly() {
        TraceStore.put("sid", "raw-session-id");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        TraceStore.put("web.naver.returnedCount", 2);
        TraceStore.put("web.naver.afterFilterCount", 1);
        QueryRequest request = new QueryRequest();
        request.query = "raw user query must not leak";
        request.threadId = "session-from-request";
        request.planId = "brave.v1";
        request.topK = 5;
        QueryResponse response = new QueryResponse();
        response.requestId = "request-raw-id";
        response.planApplied = "brave.v1";
        response.debug.put("rag.eval.queryFingerprint", Map.of(
                "queryHash", "hash-only",
                "query", "raw user query must not leak",
                "tokenBucket", "5-8"));
        response.debug.put("rag.eval.prompt", "prompt must not leak");
        Doc doc = new Doc();
        doc.source = "web";
        doc.title = "safe title";
        doc.snippet = "snippet must not leak";
        response.results.add(doc);

        service.recordRagRun(request, response, 44L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        String stored = joinedJson(entry);
        assertEquals("RAG_RUN", entry.getEntryType());
        assertNotNull(entry.getQueryHash());
        assertEquals(64, entry.getQueryHash().length());
        assertNotEquals(request.query, entry.getQueryHash());
        assertEquals(request.query.length(), entry.getQueryLength());
        assertFalse(stored.contains("raw user query must not leak"));
        assertFalse(stored.contains("snippet must not leak"));
        assertFalse(stored.contains("prompt must not leak"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void ragRunHashesFreeTextDiagnosticReasonsBeforeLedgerStorage() {
        String rawReason = "private rag eval failure api_key=redacted-test-token ownerToken=raw";
        QueryRequest request = new QueryRequest();
        request.query = "raw user query must not leak";
        QueryResponse response = new QueryResponse();
        response.debug.put("rag.eval.failureReason", rawReason);

        service.recordRagRun(request, response, 44L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        String stored = joinedJson(captor.getValue());
        assertFalse(stored.contains(rawReason), stored);
        assertFalse(stored.contains("private rag eval failure"), stored);
        assertFalse(stored.contains("redacted-test-token"), stored);
        assertFalse(stored.contains("ownerToken=raw"), stored);
        assertTrue(stored.contains("hash:"), stored);
    }

    @Test
    void asDoubleLeavesStableInvalidNumberBreadcrumbWithoutRawValue() throws Exception {
        Method method = RagOpsLedgerService.class.getDeclaredMethod("asDouble", Object.class, double.class);
        method.setAccessible(true);
        String raw = "ownerToken=raw-secret";

        Object parsed = method.invoke(null, raw, 0.25d);

        assertEquals(0.25d, (Double) parsed, 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("rag.opsLedger.suppressed.asDouble"));
        assertEquals("invalid_number", TraceStore.get("rag.opsLedger.suppressed.asDouble.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(raw), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
    }

    @Test
    void autolearnCycleStoresAggregateDecisionsOnly() {
        TraceStore.put("learning.feedback.vectorDecision", "QUARANTINE");
        TraceStore.put("learning.error.hotspot", "writer");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        CycleDiagnostics diagnostics = new CycleDiagnostics(
                4,
                0.75d,
                0.70d,
                0.60d,
                0.20d,
                0.10d,
                0.35d,
                false,
                "writer_failed",
                "BLOCK_RETRAIN",
                0.25d,
                Map.of("writer_failed", 3),
                List.of("error_rate_threshold"));

        service.recordAutolearnCycle("raw-session-id", "C:/private/train_rag.jsonl", 4, 1, false, diagnostics);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        String stored = joinedJson(entry);
        assertEquals("AUTOLEARN_CYCLE", entry.getEntryType());
        assertEquals("BLOCK_RETRAIN", entry.getDecision());
        assertEquals("writer_failed", entry.getFailureClass());
        assertEquals("writer", entry.getHotspot());
        assertFalse(stored.contains("raw-session-id"));
        assertFalse(stored.contains("train_rag.jsonl"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void autolearnCycleStoresGpuHardwareAdmissionAsRedactedMatrixSignal() {
        TraceStore.put("uaw.gpu-hardware.status", "ok");
        TraceStore.put("uaw.gpu-hardware.detectedCount", 2);
        TraceStore.put("uaw.gpu-hardware.hasRtx3090", true);
        TraceStore.put("uaw.gpu-hardware.hasRtx3060", true);
        TraceStore.put("uaw.gpu-hardware.maxMemoryUsedRatio", 0.77d);
        TraceStore.put("uaw.gpu-hardware.admission.status", "degraded");
        TraceStore.put("uaw.gpu-hardware.admission.reason", "gpu_memory_pressure");
        TraceStore.put("uaw.gpu-hardware.admission.pressureLevel", "warn");
        TraceStore.put("uaw.gpu-hardware.admission.retrainAllowed", false);
        TraceStore.put("uaw.gpu-hardware.admission.rerankAllowed", true);
        TraceStore.put("uaw.gpu-hardware.admission.embeddingFallbackAllowed", false);
        TraceStore.put("ownerToken", "owner-token-must-not-leak");

        service.recordAutolearnCycle("raw-session-id", "C:/private/train_rag.jsonl", 3, 2, false,
                new CycleDiagnostics(3, 0.80d, 0.90d, 0.90d, 0.0d, 0.0d,
                        0.0d, true, "none", "ALLOW_RETRAIN", 0.0d, Map.of(), List.of()));

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertTrue(entry.getMatrixJson().contains("uaw.gpu-hardware.admission.status"));
        assertTrue(entry.getMatrixJson().contains("uaw.gpu-hardware.admission.retrainAllowed"));
        assertTrue(entry.getMatrixJson().contains("uaw.gpu-hardware.hasRtx3090"));
        String stored = joinedJson(entry);
        assertFalse(stored.contains("raw-session-id"));
        assertFalse(stored.contains("train_rag.jsonl"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void publicRecentReturnsStructuredJsonAndRedactsStoredNestedPayloads() {
        RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
        entry.setRunId("run-1");
        entry.setEntryType("RAG_RUN");
        entry.setSourceCountsJson("""
                {
                  "resultCount": 2,
                  "nested": {
                    "query": "raw query from old row",
                    "safeCount": 2,
                    "items": [
                      {"text": "raw text from old row", "returnedCount": 3},
                      {"payload": "raw payload from old row", "afterFilterCount": 1}
                    ]
                  }
                }
                """);
        entry.setQualityJson("{\"safeHash\":\"abc\",\"answer\":\"raw answer from old row\"}");
        entry.setVectorJson("{malformed-json");
        entry.setKgJson("{\"content\":\"raw content from old row\",\"kgCount\":1}");
        entry.setMatrixJson("{\"ownerToken\":\"owner-token-must-not-leak\",\"matrixCount\":4}");
        when(repository.findRecent(isNull(), isNull(), any(Pageable.class))).thenReturn(List.of(entry));

        List<Map<String, Object>> rows = service.recent(null, null, 1);

        Map<?, ?> row = rows.get(0);
        Map<?, ?> sourceCounts = assertInstanceOf(Map.class, row.get("sourceCounts"));
        Map<?, ?> nested = assertInstanceOf(Map.class, sourceCounts.get("nested"));
        List<?> items = assertInstanceOf(List.class, nested.get("items"));
        Map<?, ?> quality = assertInstanceOf(Map.class, row.get("quality"));
        Map<?, ?> vector = assertInstanceOf(Map.class, row.get("vector"));
        Map<?, ?> kg = assertInstanceOf(Map.class, row.get("kg"));
        Map<?, ?> matrix = assertInstanceOf(Map.class, row.get("matrix"));
        String publicPayload = rows.toString();

        assertEquals(2, sourceCounts.get("resultCount"));
        assertEquals(2, nested.get("safeCount"));
        assertEquals(3, assertInstanceOf(Map.class, items.get(0)).get("returnedCount"));
        assertEquals("abc", quality.get("safeHash"));
        assertEquals(0, vector.size());
        assertEquals(1, kg.get("kgCount"));
        assertEquals(4, matrix.get("matrixCount"));
        assertFalse(publicPayload.contains("raw query from old row"));
        assertFalse(publicPayload.contains("raw text from old row"));
        assertFalse(publicPayload.contains("raw payload from old row"));
        assertFalse(publicPayload.contains("raw answer from old row"));
        assertFalse(publicPayload.contains("raw content from old row"));
        assertFalse(publicPayload.contains("owner-token-must-not-leak"));
    }

    @Test
    void publicRecentAndSummarySanitizeLegacyTopLevelLabels() {
        RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
        entry.setRunId("run ownerToken=run-secret");
        entry.setEntryType("RAG_RUN");
        entry.setSessionHash("session api_key=session-secret");
        entry.setRequestHash("request bearerToken=request-secret");
        entry.setQueryHash("query ownerToken=query-secret");
        entry.setPlanId("plan api_key=plan-secret");
        entry.setStrategyName("strategy ownerToken=strategy-secret");
        entry.setResourceTier("tier password=tier-secret");
        entry.setDecision("FAIL ownerToken=decision-secret");
        entry.setFailureClass("timeout api_key=failure-secret");
        entry.setHotspot("hotspot ownerToken=hotspot-secret");
        when(repository.findRecent(isNull(), isNull(), any(Pageable.class))).thenReturn(List.of(entry));
        when(repository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                any(LocalDateTime.class),
                any(Pageable.class))).thenReturn(List.of(entry));

        List<Map<String, Object>> rows = service.recent(null, null, 1);
        Map<String, Object> summary = service.summary(1);

        String publicPayload = rows + "|" + summary;
        assertEquals("RAG_RUN", rows.get(0).get("entryType"));
        assertFalse(publicPayload.contains("ownerToken"), publicPayload);
        assertFalse(publicPayload.contains("api_key"), publicPayload);
        assertFalse(publicPayload.contains("bearerToken"), publicPayload);
        assertFalse(publicPayload.contains("password="), publicPayload);
        assertFalse(publicPayload.contains("run-secret"), publicPayload);
        assertFalse(publicPayload.contains("session-secret"), publicPayload);
        assertFalse(publicPayload.contains("request-secret"), publicPayload);
        assertFalse(publicPayload.contains("query-secret"), publicPayload);
        assertFalse(publicPayload.contains("plan-secret"), publicPayload);
        assertFalse(publicPayload.contains("strategy-secret"), publicPayload);
        assertFalse(publicPayload.contains("tier-secret"), publicPayload);
        assertFalse(publicPayload.contains("decision-secret"), publicPayload);
        assertFalse(publicPayload.contains("failure-secret"), publicPayload);
        assertFalse(publicPayload.contains("hotspot-secret"), publicPayload);
        assertTrue(publicPayload.contains("hash:"), publicPayload);
    }

    @Test
    void publicRecentProjectsGpuAdmissionForMacMiniObserver() {
        RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
        entry.setRunId("run-gpu");
        entry.setEntryType("AUTOLEARN_CYCLE");
        entry.setMatrixJson("""
                {
                  "uaw.gpu-hardware.status": "ok",
                  "uaw.gpu-hardware.detectedCount": 2,
                  "uaw.gpu-hardware.hasRtx3090": true,
                  "uaw.gpu-hardware.hasRtx3060": true,
                  "uaw.gpu-hardware.admission.status": "degraded",
                  "uaw.gpu-hardware.admission.reason": "gpu_memory_pressure",
                  "uaw.gpu-hardware.admission.pressureLevel": "warn",
                  "uaw.gpu-hardware.admission.retrainAllowed": false,
                  "uaw.gpu-hardware.admission.rerankAllowed": true,
                  "q_gpu_hardware_pressure": 0.55,
                  "ownerToken": "owner-token-must-not-leak"
                }
                """);
        when(repository.findRecent(isNull(), isNull(), any(Pageable.class))).thenReturn(List.of(entry));

        List<Map<String, Object>> rows = service.recent(null, null, 1);

        Map<?, ?> row = rows.get(0);
        Map<?, ?> gpuAdmission = assertInstanceOf(Map.class, row.get("gpuAdmission"));
        Map<?, ?> hardware = assertInstanceOf(Map.class, gpuAdmission.get("hardware"));
        assertEquals("degraded", hardware.get("admissionStatus"));
        assertEquals("gpu_memory_pressure", hardware.get("reason"));
        assertEquals(false, hardware.get("retrainAllowed"));
        assertEquals(0.55d, ((Number) hardware.get("pressure")).doubleValue(), 0.0001d);
        assertFalse(rows.toString().contains("owner-token-must-not-leak"));
    }

    @Test
    void publicRecentRedactsLegacyPatchRationaleRows() {
        RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
        entry.setRunId("run-patch-rationale");
        entry.setEntryType("PATCH_RATIONALE");
        entry.setDecision("STORE");
        entry.setFailureClass("yaml-parse");
        entry.setSourceCountsJson("""
                {
                  "affectedSourceSet": {
                    "path": "C:/Users/nninn/private/main/resources/application.yml",
                    "active": true,
                    "sourceSetLabel": "main-java"
                  }
                }
                """);
        entry.setQualityJson("""
                {
                  "patchReason": {
                    "rawQuery": "legacy raw query must not leak",
                    "rawPrompt": "legacy raw prompt must not leak",
                    "reasonCode": "yaml_duplicate_key"
                  },
                  "verificationEvidence": {
                    "rawLog": "legacy raw verification log must not leak",
                    "passedCount": 1
                  }
                }
                """);
        entry.setVectorJson("""
                {
                  "memoryReinforcementDecision": {
                    "decision": "STORE",
                    "env": "OPENAI_API_KEY=fake-legacy-redaction-sentinel"
                  }
                }
                """);
        entry.setKgJson("""
                {
                  "callChainHint": {
                    "rawBody": "legacy raw body must not leak",
                    "depth": 2
                  }
                }
                """);
        entry.setMatrixJson("""
                {
                  "triggerTrace": {
                    "authorization": "Bearer legacy-secret-token",
                    "failureClass": "yaml-parse"
                  }
                }
                """);
        when(repository.findRecent("PATCH_RATIONALE", null, Pageable.ofSize(1))).thenReturn(List.of(entry));

        List<Map<String, Object>> rows = service.recent("PATCH_RATIONALE", null, 1);

        assertEquals("PATCH_RATIONALE", rows.get(0).get("entryType"));
        assertEquals("STORE", rows.get(0).get("decision"));
        String publicPayload = rows.toString();
        assertFalse(publicPayload.contains("legacy raw query must not leak"), publicPayload);
        assertFalse(publicPayload.contains("legacy raw prompt must not leak"), publicPayload);
        assertFalse(publicPayload.contains("legacy raw verification log must not leak"), publicPayload);
        assertFalse(publicPayload.contains("legacy raw body must not leak"), publicPayload);
        assertFalse(publicPayload.contains("legacy-secret-token"), publicPayload);
        assertFalse(publicPayload.contains("fake-legacy-redaction-sentinel"), publicPayload);
        assertFalse(publicPayload.contains("C:/Users/nninn/private"), publicPayload);
        assertTrue(publicPayload.contains("hash:"), publicPayload);
    }

    @Test
    void ragRunRedactsNestedMapListPayloadsBeforeStorage() {
        TraceStore.put("webSearch.returnedCount", 5);
        QueryRequest request = new QueryRequest();
        request.query = "raw nested query";
        QueryResponse response = new QueryResponse();
        Map<String, Object> nested = new HashMap<>();
        nested.put("safeCount", 3);
        nested.put("query", "nested raw query must not leak");
        nested.put("apiKey", "api-key-must-not-leak");
        nested.put("items", List.of(
                Map.of("content", "nested raw content must not leak", "returnedCount", 2),
                Map.of("payload", "nested raw payload must not leak", "afterFilterCount", 1)));
        response.debug.put("rag.eval.nested", nested);

        service.recordRagRun(request, response, 10L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        String stored = joinedJson(captor.getValue());
        assertFalse(stored.contains("nested raw query must not leak"));
        assertFalse(stored.contains("api-key-must-not-leak"));
        assertFalse(stored.contains("nested raw content must not leak"));
        assertFalse(stored.contains("nested raw payload must not leak"));
    }

    @Test
    void ragRunClassifiesLangGraphInvokeFallbackAsDegraded() {
        QueryRequest request = new QueryRequest();
        request.query = "langgraph fallback query";
        QueryResponse response = new QueryResponse();
        response.debug.put("langgraph.invoke.fallbackTriggered", true);
        response.debug.put("langgraph.invoke.trigger", "graph_invoke_npe");
        response.debug.put("langgraph.fallback", "sequential");
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 10L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("langgraph-invoke-fallback", entry.getFailureClass());
        assertTrue(entry.getQualityJson().contains("langgraph.invoke.fallbackTriggered"));
    }

    @Test
    void ragRunPersistsBlackboxDecisionAndRedactedMatrixProjection() {
        TraceStore.put("blackbox.risk.riskScore", 0.91d);
        TraceStore.put("blackbox.risk.dominantFailure", "provider_disabled");
        TraceStore.put("blackbox.risk.hotspot", "provider");
        TraceStore.put("blackbox.risk.restoreAction", "disable_provider_failsoft");
        TraceStore.put("blackbox.risk.patternId", "12345");
        TraceStore.put("blackbox.risk.topContributor", Map.of(
                "failureClass", "provider_disabled",
                "riskScore", 0.91d,
                "reason", "provider_disabled_signal"));
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        QueryRequest request = new QueryRequest();
        request.query = "raw blackbox query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 12L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("provider-disabled", entry.getFailureClass());
        assertEquals("provider", entry.getHotspot());
        assertTrue(entry.getMatrixJson().contains("blackbox.risk.riskScore"));
        String stored = joinedJson(entry);
        assertFalse(stored.contains("raw blackbox query must not leak"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void ragRunIgnoresNonFiniteBlackboxRiskForDecision() {
        TraceStore.put("blackbox.risk.riskScore", Double.POSITIVE_INFINITY);
        TraceStore.put("blackbox.risk.dominantFailure", "provider_disabled");
        QueryRequest request = new QueryRequest();
        request.query = "raw blackbox query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 12L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("OK", entry.getDecision());
        assertEquals("none", entry.getFailureClass());
    }

    @Test
    void ragRunClassifiesProviderCancellationSeparatelyFromTimeout() {
        TraceStore.put("web.brave.cancelled", true);
        TraceStore.put("web.brave.exceptionType", "cancelled");
        TraceStore.put("web.brave.timeout", false);
        QueryRequest request = new QueryRequest();
        request.query = "raw cancelled query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 17L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("cancelled", entry.getFailureClass());
        String stored = joinedJson(entry);
        assertFalse(stored.contains("raw cancelled query must not leak"));
    }

    @Test
    void ragRunClassifiesTavilyProviderDisabledWithStandardProviderTaxonomy() {
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", "missing_tavily_api_key");
        QueryRequest request = new QueryRequest();
        request.query = "raw tavily disabled query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 11L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("provider-disabled", entry.getFailureClass());
    }

    @Test
    void ragRunClassifiesTavilyTimeoutWithStandardProviderTaxonomy() {
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.failureReason", "timeout");
        QueryRequest request = new QueryRequest();
        request.query = "raw tavily timeout query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 13L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("timeout", entry.getFailureClass());
    }

    @Test
    void ragRunStoresTavilyProviderTaxonomyInRedactedSnapshots() {
        TraceStore.put("web.tavily.returnedCount", 4);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.afterFilterStarved", true);
        TraceStore.put("web.tavily.skipped.reason", "missing_tavily_api_key");
        QueryRequest request = new QueryRequest();
        request.query = "raw tavily snapshot query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 15L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertTrue(entry.getSourceCountsJson().contains("tavilyReturned"), entry.getSourceCountsJson());
        assertTrue(entry.getSourceCountsJson().contains("tavilyAfterFilter"), entry.getSourceCountsJson());
        assertTrue(entry.getVectorJson().contains("web.tavily.returnedCount"), entry.getVectorJson());
        assertTrue(entry.getVectorJson().contains("web.tavily.skipped.reason"), entry.getVectorJson());
        assertFalse(joinedJson(entry).contains("raw tavily snapshot query must not leak"));
    }

    @Test
    void ragRunResolvesTavilyAfterFilterDebugHotspotAsWebSearch() {
        TraceStore.put("web.tavily.afterFilterStarved", true);
        QueryRequest request = new QueryRequest();
        request.query = "raw tavily hotspot query must not leak";
        QueryResponse response = new QueryResponse();
        response.debug.put("web.tavily.afterFilterStarved", true);
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 16L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("after-filter-starvation", entry.getFailureClass());
        assertEquals("webSearch", entry.getHotspot());
        assertFalse(joinedJson(entry).contains("raw tavily hotspot query must not leak"));
    }

    @Test
    void ragRunCountsTavilySourceLabelAsWebSource() {
        QueryRequest request = new QueryRequest();
        request.query = "raw tavily source query must not leak";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "tavily";
        response.results.add(doc);

        service.recordRagRun(request, response, 17L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertTrue(entry.getSourceCountsJson().contains("\"WEB\":1"), entry.getSourceCountsJson());
        assertFalse(entry.getSourceCountsJson().contains("\"OTHER\":1"), entry.getSourceCountsJson());
        assertFalse(joinedJson(entry).contains("raw tavily source query must not leak"));
    }

    @Test
    void ragRunResolvesStageSpecificHotspotFromResponseDebugWhenTraceHasNoHotspot() {
        QueryRequest request = new QueryRequest();
        request.query = "why did reranking fail";
        QueryResponse response = new QueryResponse();
        response.debug.put("stage.onnx.failureClass", "timeout");
        response.debug.put("rag.eval.emptyResult", true);

        service.recordRagRun(request, response, 18L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("onnx", entry.getHotspot());
    }

    @Test
    void ragRunRefreshesMissingBlackboxMatrixBeforeStorage() {
        RagFailureBlackboxService blackbox = new RagFailureBlackboxService(
                emptyProvider(),
                emptyProvider(),
                emptyProvider());
        ReflectionTestUtils.setField(blackbox, "enabled", true);
        ReflectionTestUtils.setField(blackbox, "virtualPointEnabled", false);
        service = new RagOpsLedgerService(
                repository,
                new ObjectMapper(),
                emptyProvider(),
                emptyProvider(),
                provider(blackbox));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "captureRag", true);
        ReflectionTestUtils.setField(service, "maxJsonChars", 4000);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", "missing_key");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        QueryRequest request = new QueryRequest();
        request.query = "raw query must not leak into matrix";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "web";
        response.results.add(doc);

        service.recordRagRun(request, response, 21L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertTrue(TraceStore.get("blackbox.risk.matrix") instanceof Map<?, ?>);
        assertTrue(entry.getMatrixJson().contains("blackbox.risk.matrix"));
        assertTrue(entry.getMatrixJson().contains("q_signal_coverage"));
        assertTrue(entry.getMatrixJson().contains("q_failsoft_pressure"));
        String stored = joinedJson(entry);
        assertFalse(stored.contains("raw query must not leak into matrix"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void ragRunRefreshesKgNeo4jDegradationIntoRedactedBlackboxMatrix() {
        RagFailureBlackboxService blackbox = new RagFailureBlackboxService(
                emptyProvider(),
                emptyProvider(),
                emptyProvider());
        ReflectionTestUtils.setField(blackbox, "enabled", true);
        ReflectionTestUtils.setField(blackbox, "virtualPointEnabled", false);
        service = new RagOpsLedgerService(
                repository,
                new ObjectMapper(),
                emptyProvider(),
                emptyProvider(),
                provider(blackbox));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "captureRag", true);
        ReflectionTestUtils.setField(service, "maxJsonChars", 4000);
        TraceStore.put("rag.eval.kgAxis.signals", List.of("kg_neo4j_failed", "kg_neo4j_degraded"));
        TraceStore.put("rag.eval.kgAxis.neo4jStatus", "failed");
        TraceStore.put("rag.eval.kgAxis.neo4jFailureClass", "silent-failure");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");
        QueryRequest request = new QueryRequest();
        request.query = "raw kg query must not leak into ledger";
        QueryResponse response = new QueryResponse();
        Doc doc = new Doc();
        doc.source = "kg";
        response.results.add(doc);

        service.recordRagRun(request, response, 21L);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("DEGRADED", entry.getDecision());
        assertEquals("kg-neo4j-degraded", entry.getFailureClass());
        assertEquals(Boolean.TRUE, TraceStore.get("blackbox.risk.kgNeo4jDegraded"));
        assertTrue(entry.getMatrixJson().contains("blackbox.risk.kgNeo4jFailureClass"));
        assertTrue(entry.getMatrixJson().contains("q_kg_degradation_pressure"));
        assertTrue(entry.getMatrixJson().contains("q_graph_dependency_pressure"));
        String stored = joinedJson(entry);
        assertFalse(stored.contains("raw kg query must not leak into ledger"));
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void autolearnDiagnosticStoresGpuGatewayBlockAsRedactedLearningSignal() {
        RagFailureBlackboxService blackbox = new RagFailureBlackboxService(
                emptyProvider(),
                emptyProvider(),
                emptyProvider());
        ReflectionTestUtils.setField(blackbox, "enabled", true);
        service = new RagOpsLedgerService(
                repository,
                new ObjectMapper(),
                emptyProvider(),
                emptyProvider(),
                provider(blackbox));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "captureAutolearn", true);
        ReflectionTestUtils.setField(service, "maxJsonChars", 4000);
        TraceStore.put("uaw.gpu-gateway.admission.blocked", true);
        TraceStore.put("uaw.gpu-gateway.admission.blocked.count", 2);
        TraceStore.put("uaw.gpu-gateway.admission.status", "unreachable");
        TraceStore.put("learning.error.hotspot", "provider");
        TraceStore.put("ownerToken", "owner-token-must-not-leak");

        service.recordAutolearnDiagnostic("gpu_gateway_blocked", Map.of(
                "reason", "gpu_gateway_unreachable",
                "trainDecision", "BLOCK_HEAVY_WORK",
                "diagnosis", "desktop_gpu_gateway_unreachable",
                "ownerToken", "owner-token-must-not-leak",
                "gpuGatewayPreflight", Map.of(
                        "status", "unreachable",
                        "configuredCount", 3,
                        "reachableCount", 0)));

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        assertEquals("AUTOLEARN_DIAGNOSTIC", entry.getEntryType());
        assertEquals("BLOCK_HEAVY_WORK", entry.getDecision());
        assertEquals("gpu_gateway_unreachable", entry.getFailureClass());
        assertEquals("gpu_gateway", entry.getHotspot());
        assertTrue(entry.getMatrixJson().contains("q_gpu_gateway_pressure"));
        assertTrue(entry.getMatrixJson().contains("uaw.gpu-gateway.admission.status"));
        String stored = joinedJson(entry);
        assertFalse(stored.contains("owner-token-must-not-leak"));
    }

    @Test
    void patchRationaleStoresRequiredFramesWithoutRawLeakage() {
        String rawQuery = "raw patch query must not leak";
        String rawPrompt = "raw patch prompt must not leak";
        String rawAuthorization = "Authorization header patch redaction sentinel";
        String rawApiKey = "fake-api-key-patch-redaction-sentinel";
        String rawEnv = "OPENAI_API_KEY=fake-env-redaction-sentinel";
        String rawPath = "C:/Users/nninn/private/demo-1/main/resources/application.yml";
        String rawLongBody = "long body marker ".repeat(80) + "patch-secret-long-body";

        TraceStore.put("trace.id", "trace-raw-patch-id");
        TraceStore.put("yaml.duplicateKey", "onnx");
        TraceStore.put("yaml.failureClass", "yaml-parse");
        TraceStore.put("sourceSet.active", true);
        TraceStore.put("prompt.raw", rawPrompt);
        TraceStore.put("query.raw", rawQuery);
        TraceStore.put("ownerToken", "owner-token-must-not-leak");

        Map<String, Object> rationale = new HashMap<>();
        rationale.put("patchId", "patch-rationale-001");
        rationale.put("patchReason", Map.of(
                "reasonCode", "yaml_duplicate_key",
                "rawQuery", rawQuery,
                "rawPrompt", rawPrompt,
                "authorization", rawAuthorization));
        rationale.put("triggerTrace", Map.of(
                "traceKeys", List.of("yaml.duplicateKey", "yaml.failureClass"),
                "failureClass", "yaml-parse",
                "apiKey", rawApiKey));
        rationale.put("callChainHint", Map.of(
                "decisionPath", List.of("scanner", "yaml-loader", "safe-patch"),
                "rawBody", rawLongBody,
                "depth", 3));
        rationale.put("affectedSourceSet", Map.of(
                "sourceSetLabel", "main-java",
                "active", true,
                "path", rawPath,
                "fileCount", 1));
        rationale.put("verificationEvidence", Map.of(
                "commandCount", 2,
                "resultClass", "pass",
                "rawLog", rawLongBody,
                "failedCount", 0));
        rationale.put("rollbackHint", Map.of(
                "rollbackClass", "single-file-restore",
                "path", rawPath,
                "testToRerunCount", 1));
        rationale.put("memoryReinforcementDecision", Map.of(
                "decision", "STORE",
                "reasonCode", "verified_active_sourceset",
                "confidence", "high",
                "env", rawEnv));

        service.recordPatchRationale(rationale);

        ArgumentCaptor<RagOpsLedgerEntry> captor = ArgumentCaptor.forClass(RagOpsLedgerEntry.class);
        verify(repository).save(captor.capture());
        RagOpsLedgerEntry entry = captor.getValue();
        String stored = joinedJson(entry);

        assertEquals("PATCH_RATIONALE", entry.getEntryType());
        assertEquals("STORE", entry.getDecision());
        assertEquals("yaml-parse", entry.getFailureClass());
        assertEquals("main-resources", entry.getHotspot());
        assertTrue(entry.getQualityJson().contains("patchReason"), entry.getQualityJson());
        assertTrue(entry.getMatrixJson().contains("triggerTrace"), entry.getMatrixJson());
        assertTrue(entry.getKgJson().contains("callChainHint"), entry.getKgJson());
        assertTrue(entry.getSourceCountsJson().contains("affectedSourceSet"), entry.getSourceCountsJson());
        assertTrue(entry.getQualityJson().contains("verificationEvidence"), entry.getQualityJson());
        assertTrue(entry.getQualityJson().contains("rollbackHint"), entry.getQualityJson());
        assertTrue(entry.getVectorJson().contains("memoryReinforcementDecision"), entry.getVectorJson());
        assertTrue(stored.contains("hash:"), stored);
        assertFalse(stored.contains(rawQuery), stored);
        assertFalse(stored.contains(rawPrompt), stored);
        assertFalse(stored.contains(rawAuthorization), stored);
        assertFalse(stored.contains(rawApiKey), stored);
        assertFalse(stored.contains(rawEnv), stored);
        assertFalse(stored.contains(rawPath), stored);
        assertFalse(stored.contains("patch-secret-long-body"), stored);
        assertFalse(stored.contains("owner-token-must-not-leak"), stored);
    }

    private static String joinedJson(RagOpsLedgerEntry entry) {
        return String.join("|",
                String.valueOf(entry.getSourceCountsJson()),
                String.valueOf(entry.getQualityJson()),
                String.valueOf(entry.getVectorJson()),
                String.valueOf(entry.getKgJson()),
                String.valueOf(entry.getMatrixJson()));
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return provider(null);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
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
        };
    }
}
