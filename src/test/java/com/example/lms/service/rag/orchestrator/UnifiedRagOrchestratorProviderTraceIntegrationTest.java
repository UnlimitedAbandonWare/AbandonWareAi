package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorProviderTraceIntegrationTest {

    private final java.util.concurrent.atomic.AtomicReference<dev.langchain4j.rag.query.Query> lastWebQuery = new java.util.concurrent.atomic.AtomicReference<>();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("analyzeWebSearchRetriever", ContentRetriever.class, () -> query -> {
                lastWebQuery.set(query);
                TraceStore.put("webSearch.returnedCount", 3);
                TraceStore.put("webSearch.afterFilterCount", 0);
                TraceStore.put("rag.returnedCount", 3);
                TraceStore.put("rag.afterFilterCount", 0);
                return List.of();
            })
            .withBean(UnifiedRagOrchestrator.class);

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void preclassifiedRequestSkipsExtraAnalysisWhileDefaultStillAnalyzes() {
        var analyzer = org.mockito.Mockito.mock(com.example.lms.service.rag.query.QueryAnalysisService.class);
        contextRunner.run(context -> {
            var orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            org.springframework.test.util.ReflectionTestUtils.setField(orchestrator, "queryAnalysisService", analyzer);
            var request = new UnifiedRagOrchestrator.QueryRequest();
            request.query = "synthetic preclassified question";
            request.useVector = false; request.useKg = false; request.useBm25 = false;
            request.enableBiEncoder = false; request.enableDiversity = false; request.enableOnnx = false;
            var ordinary = orchestrator.query(request);
            org.mockito.Mockito.verify(analyzer).analyze(request.query);
            org.junit.jupiter.api.Assertions.assertEquals(false, ordinary.debug.get("analysis.skipped"));
            org.mockito.Mockito.clearInvocations(analyzer);
            request.enableQueryAnalysis = false;
            request.webQueryAlreadyPlanned = true;
            request.webTopK = 3;
            var preclassified = orchestrator.query(request);
            org.mockito.Mockito.verifyNoInteractions(analyzer);
            org.junit.jupiter.api.Assertions.assertEquals(true, preclassified.debug.get("analysis.skipped"));
            assertTrue(preclassified.debug.get("analysis.elapsedMs") instanceof Long);
            var metadata = com.example.lms.service.rag.QueryUtils.metadata(lastWebQuery.get());
            org.junit.jupiter.api.Assertions.assertEquals(true, metadata.get("webQueryAlreadyPlanned"));
            org.junit.jupiter.api.Assertions.assertEquals(3, metadata.get("webTopK"));
        });
    }

    @Test
    void realQueryPathConvertsProviderTraceStarvationIntoRagEvalSignalsWithoutRawQuery() {
        TraceStore.clear();

        contextRunner.run(context -> {
            UnifiedRagOrchestrator orchestrator = context.getBean(UnifiedRagOrchestrator.class);
            UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
            request.query = "raw starvation query should not leak";
            request.useWeb = true;
            request.useVector = false;
            request.useKg = false;
            request.useBm25 = false;
            request.enableBiEncoder = false;
            request.enableDiversity = false;
            request.enableOnnx = false;
            request.topK = 5;

            UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);
            org.junit.jupiter.api.Assertions.assertEquals("other", response.debug.get("web.retriever"));

            @SuppressWarnings("unchecked")
            List<String> starvationSignals =
                    (List<String>) response.debug.get("rag.eval.afterFilterStarvationSignals");
            assertTrue(starvationSignals.stream().anyMatch(signal -> signal.startsWith("webSearch:")));
            assertTrue(starvationSignals.stream().anyMatch(signal -> signal.startsWith("rag:")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> thresholdBreaks =
                    (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
            assertTrue(thresholdBreaks.stream()
                    .anyMatch(row -> "after_filter_starvation".equals(row.get("label"))));

            @SuppressWarnings("unchecked")
            Map<String, Object> bottleneck =
                    (Map<String, Object>) response.debug.get("rag.eval.bottleneck");
            assertTrue(bottleneck.containsKey("label"));

            @SuppressWarnings("unchecked")
            Map<String, Object> fingerprint =
                    (Map<String, Object>) response.debug.get("rag.eval.queryFingerprint");
            assertTrue(fingerprint.containsKey("queryHash"));
            assertTrue(fingerprint.containsKey("length"));
            assertTrue(fingerprint.containsKey("tokenBucket"));

            assertFalse(String.valueOf(response.debug).contains("raw starvation query should not leak"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains("raw starvation query should not leak"));
        });
    }
}
