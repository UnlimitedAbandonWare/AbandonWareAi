package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.routing.plan.RoutingPlanService;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrieverFailSoftBreadcrumbTest {

    @Test
    void smallRagFallbacksLeaveStageBreadcrumbs() throws Exception {
        String analyze = read("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java");
        String quality = read("main/java/com/example/lms/service/rag/AnswerQualityEvaluator.java");
        String biEncoder = read("main/java/com/example/lms/service/rag/BiEncoderReranker.java");
        String contextOrchestrator = read("main/java/com/example/lms/service/rag/ContextOrchestrator.java");
        String embeddingCrossEncoder = read("main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java");
        String hybridRetriever = read("main/java/com/example/lms/service/rag/HybridRetriever.java");
        String hybridMetadata = read("main/java/com/example/lms/service/rag/HybridRetrieverMetadata.java");
        String hybridSupport = read("main/java/com/example/lms/service/rag/HybridRetrieverSupport.java");
        String ragService = read("main/java/com/example/lms/service/rag/LangChainRAGService.java");
        String tavily = read("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java");
        String authority = read("main/java/com/example/lms/service/rag/auth/AuthorityScorer.java");
        String domainWhitelist = read("main/java/com/example/lms/service/rag/auth/DomainWhitelist.java");

        assertSlf4jStage(analyze, "Analyze", "partialSearch.interrupted");
        assertSlf4jStage(analyze, "Analyze", "cancelFuture");
        assertSlf4jStage(analyze, "AnalyzeWebSearchRetriever", "metaInt.parse");
        assertSlf4jStage(quality, "CRAG][eval", "isSufficient");
        assertSlf4jStage(biEncoder, "BiEncoderReranker", "rerank");
        assertSlf4jStage(contextOrchestrator, "ContextOrchestrator", "isAlreadyCompressed");
        assertSlf4jStage(contextOrchestrator, "ContextOrchestrator", "tracePromptComposerSkipped");
        assertSlf4jStage(contextOrchestrator, "ContextOrchestrator", "traceMemoryComposerSkipped");
        assertSlf4jStage(contextOrchestrator, "ContextOrchestrator", "isWebContent");
        assertSlf4jStage(embeddingCrossEncoder, "Rerank", "rules.rerank");
        assertSlf4jStage(embeddingCrossEncoder, "Rerank", "safeUrl");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.onnxBreaker");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.backend.trace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.backend.debugEvent");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "branchFailure.trace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "branchFailure.debugEvent");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "retrieve.metadata");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "prefuseCap.parse");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "prefuseCap.trace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "prefuseCap.queryRebuild");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "domain.detect");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "intent.metadata");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "education.sort");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "education.shortCircuit");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "implicitConsistency.trace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "officialDomain.metadata");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "implicitConsistency.pathFormation");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "implicitConsistency.traceStore");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "progressive.domain.detect");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "progressive.prefuseCap.parse");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "progressiveHints.domain.detect");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "progressiveHints.prefuseCap.parse");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "progressiveHints.prefuseCap.metadata");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "retrieveAll.prefuseCap.parse");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "retrieveAll.outer");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "stateDriven.queryTransformer");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "cosineSimilarity");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "relatedness.prefilter");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "elementConstraint.rescore");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerankGate.shouldRerank");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.skippedSuppressedTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.noRerankerTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.cooldownTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.cooldownLock");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "retrieveAll.async.handler");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "retrieveAll.async.join");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.executedTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.executedFallbackTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.errorTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "rerank.gateTrace");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "relatedness.scoreFusion");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "softmax.weights");
        assertSlf4jStage(hybridRetriever, "HybridRetriever", "relatedness.bucketFusion");
        assertSlf4jStage(hybridMetadata, "HybridRetrieverMetadata", "metadata.toMap");
        assertSlf4jStage(hybridMetadata, "HybridRetrieverMetadata", "metaInt.parse");
        assertSlf4jStage(hybridSupport, "HybridRetrieverSupport", "toIntegerOrNull");
        assertSlf4jStage(ragService, "LangChainRAGService", "activeGlobalSid");
        assertSlf4jStage(ragService, "LangChainRAGService", "traceVectorRetrievalStart");
        assertSlf4jStage(ragService, "LangChainRAGService", "traceVectorRetrievalResult");
        assertSlf4jStage(ragService, "LangChainRAGService", "traceVectorFilterRelaxed");
        assertSlf4jStage(ragService, "LangChainRAGService", "logVectorState");
        assertSlf4jStage(ragService, "LangChainRAGService", "sidRotationAdvisor.recordPoison");
        assertSlf4jStage(ragService, "LangChainRAGService", "contentRetriever.retrieve");
        assertSlf4jStage(ragService, "LangChainRAGService", "globalKbDomain.metadata");
        assertSlf4jStage(ragService, "LangChainRAGService", "retrieveGlobalKbDomain");
        assertSlf4jStage(ragService, "LangChainRAGService", "resolveMinScore.parse");
        assertSlf4jStage(ragService, "LangChainRAGService", "metaInt.parse");
        assertSlf4jStage(ragService, "LangChainRAGService", "buildFilterForSid.and");
        assertSlf4jStage(ragService, "LangChainRAGService", "buildSidOnlyFilterForSid");
        assertSlf4jStage(ragService, "LangChainRAGService", "andSafe");
        assertSlf4jStage(ragService, "LangChainRAGService", "retrieveRagContext.sidRotationAdvisor");
        assertSlf4jStage(ragService, "LangChainRAGService", "retrieveRagContext");
        assertSlf4jStage(tavily, "TavilyWebSearchRetriever", "retryAfter.seconds");
        assertSlf4jStage(tavily, "TavilyWebSearchRetriever", "retryAfter.httpDate");
        assertSlf4jStage(tavily, "TavilyWebSearchRetriever", "endpointHost");
        assertSlf4jStage(authority, "AuthorityScorer", "parse.weight");
        assertSlf4jStage(authority, "AuthorityScorer", "host.uri");
        assertSlf4jStage(domainWhitelist, "DomainWhitelist", "extractHost");
    }

    @Test
    void analyzeWebSearchMetaIntDropsNonFiniteNumbers() throws Exception {
        Method method = AnalyzeWebSearchRetriever.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        method.setAccessible(true);

        assertEquals(7, method.invoke(null, Map.of("topK", Double.POSITIVE_INFINITY), "topK", 7));
        assertEquals(7, method.invoke(null, Map.of("topK", Double.NaN), "topK", 7));
    }

    @Test
    void analyzeWebSearchPartialFutureFailureLeavesRedactedTraceAndUsesOriginalFallback() {
        String rawQuery = "analyze partial future query api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RoutingPlanService routing = org.mockito.Mockito.mock(RoutingPlanService.class);
        org.mockito.Mockito.when(routing.plan(
                        org.mockito.ArgumentMatchers.eq(rawQuery),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of("planned error query"));
        AnalyzeWebSearchRetriever retriever = new AnalyzeWebSearchRetriever(
                null,
                new ThrowingErrorProvider("planned error query", rawQuery),
                original -> original,
                routing,
                new SearchPolicyEngine(),
                executor);

        try {
            List<Content> out = retriever.retrieve(QueryUtils.buildQuery(rawQuery, Map.of()));

            assertEquals(1, out.size());
            assertEquals(Boolean.TRUE, TraceStore.get("web.analyze.partialSearch.failed"));
            assertEquals("exception", TraceStore.get("web.analyze.partialSearch.failureReason"));
            assertEquals("AssertionError", TraceStore.get("web.analyze.partialSearch.errorType"));
            assertTrue(String.valueOf(TraceStore.get("web.analyze.partialSearch.queryHash12")).matches("[0-9a-f]{12}"));
            assertEquals(rawQuery.length(), TraceStore.get("web.analyze.partialSearch.queryLength"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        } finally {
            executor.shutdownNow();
            TraceStore.clear();
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertSlf4jStage(String source, String component, String stage) {
        boolean stageOnly = source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")");
        boolean stageWithErrorType = source.contains("log.debug(\"[" + component + "] fail-soft stage={} errorType={}\",")
                && source.contains("\"" + stage + "\"");
        assertTrue(stageOnly || stageWithErrorType,
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static final class ThrowingErrorProvider implements WebSearchProvider {
        private final String plannedQuery;
        private final String originalQuery;

        private ThrowingErrorProvider(String plannedQuery, String originalQuery) {
            this.plannedQuery = plannedQuery;
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            if (plannedQuery.equals(query)) {
                throw new AssertionError("raw partial api_key=" + com.example.lms.test.SecretFixtures.openAiKey());
            }
            if (originalQuery.equals(query)) {
                return List.of("original fallback after partial failure");
            }
            return List.of();
        }

        @Override
        public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
            return new NaverSearchService.SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing-error";
        }
    }
}
