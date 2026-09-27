package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.nova.protocol.alloc.SimpleRiskKAllocator;
import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.properties.NovaNextProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedRagOrchestratorDependencyHonestyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    /**
     * Unified VECTOR axis resolves through LangChainRAGService.asContentRetriever
     * (the pure leaf). Tests that need a controllable vector leg inject it here;
     * the legacy 'vectorRetriever' hybrid bean is never consulted by this axis.
     */
    private static void wireVectorLeaf(UnifiedRagOrchestrator orchestrator, ContentRetriever retriever) {
        LangChainRAGService ragService = org.mockito.Mockito.mock(LangChainRAGService.class);
        org.mockito.Mockito.when(ragService.asContentRetriever(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(retriever);
        ReflectionTestUtils.setField(orchestrator, "langChainRAGService", ragService);
    }

    @Test
    void vectorFailurePreservesIndependentWebAndBm25Results() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "webRetriever", (ContentRetriever) query -> List.of(
                Content.from(TextSegment.from("preserve web evidence", Metadata.from("url", "https://example.org/source")))));
        wireVectorLeaf(orchestrator, query -> {
            throw new IllegalStateException("embedding_space_unverified");
        });
        var index = new com.example.lms.service.service.rag.bm25.Bm25Index();
        index.put(new com.example.lms.service.service.rag.bm25.Bm25Index.Doc("kept", "preserve"));
        index.put(new com.example.lms.service.service.rag.bm25.Bm25Index.Doc("other-1", "unrelated"));
        index.put(new com.example.lms.service.service.rag.bm25.Bm25Index.Doc("other-2", "different"));
        ReflectionTestUtils.setField(orchestrator, "bm25Index", index);
        var request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "preserve";
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = true;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        var response = orchestrator.query(request);

        assertEquals("failed:vector_retrieval_failed", response.debug.get("stage.vector"));
        assertEquals("ok:1", response.debug.get("stage.bm25"));
        assertTrue(response.results.stream().anyMatch(doc -> "BM25".equals(doc.source)));
        assertTrue(response.results.stream().anyMatch(doc -> "WEB".equals(doc.source)));
    }

    @Test
    void missingRetrieversAreNotReportedAsSuccessfulEmptyStages() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "dependency honesty";
        request.useWeb = true;
        request.useVector = true;
        request.useKg = true;
        request.useBm25 = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals("missing_webRetriever", response.debug.get("stage.web"));
        assertEquals("unavailable_pure_vector_leaf", response.debug.get("stage.vector"));
        assertEquals("missing_kgRetriever", response.debug.get("stage.kg"));
        assertEquals("missing_bean", response.debug.get("retrieval.dependency.web.status"));
        assertEquals("missing_bean", response.debug.get("retrieval.dependency.vector.status"));
        assertEquals("missing_bean", response.debug.get("retrieval.dependency.kg.status"));
        assertEquals("missing-dependency", response.debug.get("retrieval.dependency.web.failureClass"));
        assertEquals("missing-dependency", response.debug.get("retrieval.dependency.vector.failureClass"));
        assertEquals("missing-dependency", response.debug.get("retrieval.dependency.kg.failureClass"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.web.fallbackUsed"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.vector.fallbackUsed"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.kg.fallbackUsed"));
    }

    @Test
    void orchestratorLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("dbg.put(\"stage.dpp\", \"error: \" + t.toString());"));
        assertFalse(source.contains(
                "dbg.put(\"stage.dpp\", \"error: \" + com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(t), 180));"));
        assertFalse(source.contains(
                "dbg.put(\"stage.onnx\", \"error: \" + com.example.lms.trace.SafeRedactor.safeMessage(String.valueOf(t), 180));"));
        assertTrue(source.contains(
                "dbg.put(\"stage.dpp\", \"error: \" + com.example.lms.trace.SafeRedactor.traceLabelOrFallback(t.getMessage(), \"\"));"));
        assertTrue(source.contains(
                "dbg.put(\"stage.onnx\", \"error: \" + com.example.lms.trace.SafeRedactor.traceLabelOrFallback(t.getMessage(), \"\"));"));
        assertFalse(source.contains("\"exceptionType\", t.getClass().getSimpleName()"));
        assertTrue(source.contains("\"exceptionType\", \"dpp_rerank_failed\""));
        assertFalse(source.contains("\"exceptionType\", error == null ? \"\" : error.getClass().getSimpleName()"));
        assertTrue(source.contains("\"exceptionType\", safeFailure"));
    }

    @Test
    void ragEvalEventStoresRequestIdAsHashOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("data.put(\"requestId\", resp.requestId);"));
        assertTrue(source.contains("data.put(\"requestIdHash\", com.example.lms.trace.SafeRedactor.hashValue(resp.requestId));"));
    }

    @Test
    void bestEffortFallbacksLeaveScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.dpp\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.toDouble\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.traceInt\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.seed\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.rrf.defaults\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.rrf.webRich\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.onnxDocIndex\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rag.orchestrator.suppressed.kgRelationThumbnailInt\", true)"));
        assertTrue(source.contains("stage=ragPipelineEvent"));
        assertTrue(source.contains("stage=kgRelationThumbnail.trace"));
        assertTrue(source.contains("stage=dependency.trace"));
        assertTrue(source.contains("stage=dependency.faultMask"));
        assertTrue(source.contains("stage=dependency.debugEvent"));
    }

    @Test
    void numericFallbacksUseStableInvalidNumberLabels() {
        TraceStore.clear();
        Content content = Content.from(TextSegment.from("doc",
                Metadata.from(Map.of("_awx.onnxDocIndex", "not-an-index"))));

        assertEquals(0.5d,
                (Double) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "toDouble", "not-a-double", 0.5d),
                1.0e-9d);
        assertEquals(0.5d,
                (Double) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "toDouble", Double.POSITIVE_INFINITY, 0.5d),
                1.0e-9d);
        TraceStore.put("bad.traceInt", "not-an-int");
        assertEquals(0,
                (Integer) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "traceInt", "bad.traceInt"));
        TraceStore.put("bad.traceInt", Double.NaN);
        assertEquals(0,
                (Integer) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "traceInt", "bad.traceInt"));
        assertNull(ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                "onnxDocIndex", content));
        Content nonFiniteIndex = Content.from(TextSegment.from("doc",
                Metadata.from(Map.of("_awx.onnxDocIndex", Double.POSITIVE_INFINITY))));
        assertNull(ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                "onnxDocIndex", nonFiniteIndex));
        assertEquals(0,
                (Integer) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "relationThumbnailInt", "not-a-thumbnail-int"));
        assertEquals(0,
                (Integer) ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class,
                        "relationThumbnailInt", Double.POSITIVE_INFINITY));

        assertEquals("invalid_number", TraceStore.get("rag.orchestrator.suppressed.toDouble.errorType"));
        assertEquals("invalid_number", TraceStore.get("rag.orchestrator.suppressed.traceInt.errorType"));
        assertEquals("invalid_number", TraceStore.get("rag.orchestrator.suppressed.onnxDocIndex.errorType"));
        assertEquals("invalid_number", TraceStore.get("rag.orchestrator.suppressed.kgRelationThumbnailInt.errorType"));
    }

    @Test
    void copyMetadataDropsCredentialAndRawContentKeysFromPublicDocMeta() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("score", 0.87d);
        raw.put("source", "web");
        raw.put("apiKey", "api-key-must-not-leak");
        raw.put("ownerToken", "owner-token-must-not-leak");
        raw.put("rawQuery", "raw query must not leak");
        raw.put("content", "raw content must not leak");
        Map<String, Object> target = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(UnifiedRagOrchestrator.class, "copyMetadata", raw, target);

        assertEquals(0.87d, (Double) target.get("score"), 1.0e-9d);
        assertEquals("web", target.get("source"));
        String publicMeta = target.toString();
        assertFalse(publicMeta.contains("apiKey"), publicMeta);
        assertFalse(publicMeta.contains("ownerToken"), publicMeta);
        assertFalse(publicMeta.contains("rawQuery"), publicMeta);
        assertFalse(publicMeta.contains("content"), publicMeta);
        assertFalse(publicMeta.contains("api-key-must-not-leak"), publicMeta);
        assertFalse(publicMeta.contains("owner-token-must-not-leak"), publicMeta);
        assertFalse(publicMeta.contains("raw query must not leak"), publicMeta);
        assertFalse(publicMeta.contains("raw content must not leak"), publicMeta);
    }

    @Test
    void retrievalExceptionsAreStableDependencyFailuresNotEmptyResults() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "webRetriever", (ContentRetriever) query -> {
            throw new IllegalStateException("web timeout while reading provider");
        });
        wireVectorLeaf(orchestrator, query -> {
            throw new IllegalStateException("vector timeout while reading store");
        });
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", (ContentRetriever) query -> {
            throw new IllegalArgumentException("kg credential unavailable");
        });

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "dependency failure should not look empty";
        request.useWeb = true;
        request.useVector = true;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals("failed:web_retrieval_failed", response.debug.get("stage.web"));
        assertEquals("failed:vector_retrieval_failed", response.debug.get("stage.vector"));
        assertEquals("failed:kg_retrieval_failed", response.debug.get("stage.kg"));
        assertFalse(String.valueOf(response.debug).contains("IllegalStateException"));
        assertFalse(String.valueOf(response.debug).contains("IllegalArgumentException"));
        assertFalse(String.valueOf(response.debug).contains("timeout while reading"));
        assertFalse(String.valueOf(response.debug).contains("credential unavailable"));
        assertEquals("failed", response.debug.get("retrieval.dependency.web.status"));
        assertEquals("failed", response.debug.get("retrieval.dependency.vector.status"));
        assertEquals("failed", response.debug.get("retrieval.dependency.kg.status"));
        assertEquals("timeout", response.debug.get("retrieval.dependency.web.failureClass"));
        assertEquals("timeout", response.debug.get("retrieval.dependency.vector.failureClass"));
        assertEquals("provider-disabled", response.debug.get("retrieval.dependency.kg.failureClass"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.web.fallbackUsed"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.vector.fallbackUsed"));
        assertEquals(Boolean.TRUE, response.debug.get("retrieval.dependency.kg.fallbackUsed"));
        assertEquals("failed", TraceStore.get("retrieval.dependency.web.status"));
        assertEquals("failed", TraceStore.get("retrieval.dependency.vector.status"));
        assertEquals("failed", TraceStore.get("retrieval.dependency.kg.status"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.web"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.vector"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.kg"));
    }

    @Test
    void negativeWebTopKIsRejectedBeforeWebRetrieverExecution() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        AtomicInteger webAttempts = new AtomicInteger();
        ReflectionTestUtils.setField(orchestrator, "webRetriever", (ContentRetriever) query -> {
            webAttempts.incrementAndGet();
            return List.of(Content.from(TextSegment.from("must-not-retrieve")));
        });

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "negative top-k admission";
        request.webTopK = -1;
        request.useWeb = true;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        assertThrows(IllegalArgumentException.class, () -> orchestrator.query(request));
        assertEquals(0, webAttempts.get());
    }

    @Test
    void seedOnlyWebTopKZeroReturnsNoCandidates() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "seed zero";
        request.topK = 3;
        request.webTopK = 0;
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        request.seedWeb = List.of(Content.from(TextSegment.from("must-not-escape")));

        assertTrue(orchestrator.query(request).results.isEmpty());
    }

    @Test
    void emptyWebAndVectorRetrieversUseStableEmptyResultLabels() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "webRetriever", (ContentRetriever) query -> List.of());
        wireVectorLeaf(orchestrator, query -> List.of());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "empty retrieval should not look like an exception";
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals("empty_result", response.debug.get("stage.web"));
        assertEquals("empty_result", response.debug.get("stage.vector"));
        assertEquals("ready", response.debug.get("retrieval.dependency.web.status"));
        assertEquals("ready", response.debug.get("retrieval.dependency.vector.status"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.web"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.vector"));
    }

    @Test
    void vectorFallbackRecordsRedactedTraceBreadcrumbsWhenWebIsEmpty() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "webRetriever", (ContentRetriever) query -> List.of());
        wireVectorLeaf(orchestrator, query ->
                List.of(Content.from(TextSegment.from("fallback vector evidence"))));

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "vector fallback raw query ownerToken=secret";
        request.topK = 3;
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals("triggered", response.debug.get("stage.vector.fallback"));
        assertEquals(Boolean.TRUE, TraceStore.get("vectorFallback.used"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.vectorFallback.used"));
        assertEquals("web_empty", TraceStore.get("retrieval.vectorFallback.reason"));
        assertEquals(10, TraceStore.get("retrieval.vectorFallback.effectiveTopK"));
        assertEquals(12, String.valueOf(TraceStore.get("retrieval.vectorFallback.queryHash12")).length());
        assertEquals(request.query.length(), TraceStore.get("retrieval.vectorFallback.queryLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("vector fallback raw query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret"));
    }

    @Test
    void emptyKgRetrieverIsOptionalEmptyNotFailure() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", (ContentRetriever) query -> List.of());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "query-time retrieval can proceed without a pre-built corpus graph";
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals("empty", response.debug.get("stage.kg"));
        assertEquals("ready", response.debug.get("retrieval.dependency.kg.status"));
        assertNotEquals("failed:EmptyResult", response.debug.get("stage.kg"));
    }

    @Test
    void diversityRerankUsesInjectedDppWithoutEmbeddingModel() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "dppDiversityReranker", new InjectedDppReranker());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "DPP should use the Spring-managed reranker";
        request.topK = 4;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.seedOnly = true;
        request.enableBiEncoder = false;
        request.enableDiversity = true;
        request.enableOnnx = false;
        request.seedCandidates = List.of(
                seedDoc("a", "same family evidence", 1.0d),
                seedDoc("b", "same family evidence duplicate", 0.9d),
                seedDoc("c", "different authority evidence", 0.8d),
                seedDoc("d", "another independent evidence", 0.7d));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals(Boolean.TRUE, TraceStore.get("test.dpp.injected"));
        assertTrue(((Number) response.debug.get("stage.dpp")).intValue() > 0);
        assertFalse(String.valueOf(response.debug).contains("disabled:no_embedding_model"));
    }

    @Test
    void finalTopKTrimRunsAfterDiversityAugmentation() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "dppDiversityReranker", new AugmentingDppReranker());

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "post rerank trim";
        request.topK = 2;
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = true;
        request.enableOnnx = false;
        request.seedCandidates = List.of(
                seedDoc("first", "first evidence", 1.0d),
                seedDoc("second", "second evidence", 0.9d));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(Boolean.TRUE, TraceStore.get("test.dpp.augmented"));
        assertEquals(List.of("first", "second"),
                response.results.stream().map(doc -> doc.id).toList());
    }

    @Test
    void seedOnlyFusionPublishesHypernovaTraceWhenNovaNextIsInjected() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        NovaNextProperties props = new NovaNextProperties();
        props.setKTotal(12);
        ReflectionTestUtils.setField(orchestrator, "novaNextFusionService",
                new NovaNextFusionService(props, new SimpleRiskKAllocator()));

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "private hypernova seed fusion query ownerToken=secret";
        request.topK = 4;
        request.seedOnly = true;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        request.seedCandidates = List.of(
                seedDoc("tail", "high authority tail evidence", 0.95d, "WEB"),
                seedDoc("normal", "normal evidence", 0.70d, "VECTOR"),
                seedDoc("risk", "contradictory risk evidence", 0.65d, "KG"),
                seedDoc("support", "supporting evidence", 0.60d, "BM25"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertNotNull(response);
        assertEquals(4, response.results.size());
        assertTrue(TraceStore.get("hypernova.twpmP") instanceof Double);
        assertTrue(TraceStore.get("hypernova.cvarPhi") instanceof Double);
        assertEquals(Boolean.TRUE, TraceStore.get("nova.hypernova.riskK.used"));
        assertEquals(12, TraceStore.get("nova.hypernova.riskK.alloc.sum"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private hypernova seed fusion query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret"));
    }

    private static UnifiedRagOrchestrator.Doc seedDoc(String id, String snippet, double score) {
        return seedDoc(id, snippet, score, "SEED");
    }

    private static UnifiedRagOrchestrator.Doc seedDoc(String id, String snippet, double score, String source) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = id;
        doc.snippet = snippet;
        doc.source = source;
        doc.score = score;
        return doc;
    }

    private static final class InjectedDppReranker extends DppDiversityReranker {
        @Override
        public <T> List<T> rerank(Config callConfig,
                                  List<T> in,
                                  String query,
                                  int k,
                                  Function<? super T, String> textOf,
                                  ToDoubleFunction<? super T> relevanceOf,
                                  Function<? super T, String> stableKeyOf) {
            TraceStore.put("test.dpp.injected", true);
            return in;
        }
    }

    private static final class AugmentingDppReranker extends DppDiversityReranker {
        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> rerank(Config callConfig,
                                  List<T> in,
                                  String query,
                                  int k,
                                  Function<? super T, String> textOf,
                                  ToDoubleFunction<? super T> relevanceOf,
                                  Function<? super T, String> stableKeyOf) {
            TraceStore.put("test.dpp.augmented", true);
            List<T> augmented = new java.util.ArrayList<>(in);
            augmented.add((T) seedDoc("late", "post rerank augmentation", 0.8d));
            return augmented;
        }
    }
}
