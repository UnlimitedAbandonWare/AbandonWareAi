package com.example.lms.service.rag;

import com.example.lms.learning.NeuralPathFormationService;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.fusion.ReciprocalRankFuser;
import com.example.lms.service.rag.handler.RetrievalHandler;
import com.example.lms.service.rag.rerank.ElementConstraintScorer;
import com.example.lms.service.rag.rerank.LightWeightRanker;
import com.example.lms.service.rag.rerank.RerankGate;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverImplicitConsistencyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void pureMetadataHelpersLiveOutsideHybridRetrieverLargeFile() throws Exception {
        Path retrieverPath = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
        Path helperPath = Path.of("main/java/com/example/lms/service/rag/HybridRetrieverMetadata.java");

        String retriever = Files.readString(retrieverPath);

        assertTrue(Files.exists(helperPath), "metadata helper should reduce HybridRetriever file size");
        String helper = Files.readString(helperPath);
        assertTrue(retriever.contains("import static com.example.lms.service.rag.HybridRetrieverMetadata.*;"));
        assertFalse(retriever.contains("private static Map<String, Object> toMap("));
        assertFalse(retriever.contains("private static int metaInt("));
        assertFalse(retriever.contains("private static void addCapped("));
        assertFalse(retriever.contains("private String buildDedupeKey("));
        assertTrue(helper.contains("final class HybridRetrieverMetadata"));
        assertTrue(helper.contains("static String buildDedupeKey"));
    }

    @Test
    void hybridRetrieverTraceSuppressionsNormalizeNumericErrorType() {
        HybridRetrieverTraceSuppressions.trace(
                "metadata.scoreParse",
                new NumberFormatException("ownerToken=raw-secret"));

        assertEquals("invalid_number",
                TraceStore.get("hybrid.retriever.suppressed.metadata.scoreParse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("ownerToken=raw-secret"), trace);
    }

    @Test
    void hybridRetrieverTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        String rawStage = "metadata.scoreParse " + com.example.lms.test.SecretFixtures.openAiKey();

        HybridRetrieverTraceSuppressions.trace(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("hybrid.retriever.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("hybrid.retriever.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("hybrid.retriever.suppressed.errorType"));
        assertEquals("IllegalStateException",
                TraceStore.get("hybrid.retriever.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    @Test
    void hybridNumericMetadataParsersOnlyCatchNumberFormatException() throws Exception {
        String metadata = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/HybridRetrieverMetadata.java"));
        String support = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/HybridRetrieverSupport.java"));

        assertParserCatchNarrowed(metadata, "static int metaInt(Map<String, Object> md, String key, int defaultValue)");
        assertParserCatchNarrowed(support, "private static Integer toIntegerOrNull(Object value)");
    }

    @Test
    void hybridNumericMetadataParsersDropNonFiniteNumbers() {
        assertEquals(5, HybridRetrieverMetadata.metaInt(
                Map.of("topK", Double.POSITIVE_INFINITY), "topK", 5));
        assertEquals(List.of(2), HybridRetrieverSupport.toIntList(List.of(Double.NaN, 2)));
    }

    @Test
    void hybridRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "hybrid retriever fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void implicitConsistencyIsAuxiliaryOnlyByDefault() {
        AdaptiveScoringService scoring = mock(AdaptiveScoringService.class);
        KnowledgeBaseService kb = knowledgeBase();
        NeuralPathFormationService pathFormation = mock(NeuralPathFormationService.class);
        HybridRetriever retriever = retriever(scoring, kb, pathFormation);

        invokeImplicitConsistency(retriever);

        verify(scoring, never()).applyImplicitPositive(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(pathFormation, never()).maybeFormPath(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        assertEquals("auxiliary_only", TraceStore.get("retrieval.implicitConsistency.status"));
        assertFalse((Boolean) TraceStore.get("retrieval.implicitConsistency.persistEnabled"));
    }

    @Test
    void implicitConsistencyPersistsOnlyWhenExplicitlyEnabled() {
        AdaptiveScoringService scoring = mock(AdaptiveScoringService.class);
        KnowledgeBaseService kb = knowledgeBase();
        NeuralPathFormationService pathFormation = mock(NeuralPathFormationService.class);
        HybridRetriever retriever = retriever(scoring, kb, pathFormation);
        ReflectionTestUtils.setField(retriever, "implicitConsistencyPersistEnabled", true);

        invokeImplicitConsistency(retriever);

        verify(scoring).applyImplicitPositive("GENERAL", "Alpha", "Beta", 1.0d);
        verify(pathFormation).maybeFormPath("Alpha->Beta", 1.0d);
        assertEquals("persisted", TraceStore.get("retrieval.implicitConsistency.status"));
        assertEquals(12, String.valueOf(TraceStore.get("retrieval.implicitConsistency.subjectHash12")).length());
    }

    @Test
    void stateDrivenRetrievalFallsBackToRawQueryWhenTransformerTimesOut() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transformEnhanced(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout from test"));

        List<String> handledQueries = new CopyOnWriteArrayList<>();
        RetrievalHandler handler = (query, accumulator) -> {
            handledQueries.add(query.text());
            accumulator.add(Content.from("GraphRAG relation thumbnail evidence"));
        };

        HybridRetriever retriever = retriever(
                mock(AdaptiveScoringService.class),
                knowledgeBase(),
                mock(NeuralPathFormationService.class),
                transformer,
                handler);

        List<Content> out = retriever.retrieveStateDriven(
                PromptContext.builder()
                        .userQuery("GraphRAG relation thumbnail")
                        .lastAssistantAnswer("assistant draft")
                        .subject("GraphRAG")
                        .build(),
                1);

        assertEquals(List.of("GraphRAG relation thumbnail"), handledQueries);
        assertFalse(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("timeout", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
    }

    @Test
    void stateDrivenRetrievalClassifiesBreakerOpenWithoutRawExceptionLeak() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transformEnhanced(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("breaker_open ownerToken=secret"));

        List<String> handledQueries = new CopyOnWriteArrayList<>();
        RetrievalHandler handler = (query, accumulator) -> {
            handledQueries.add(query.text());
            accumulator.add(Content.from("Breaker-open fallback evidence"));
        };

        HybridRetriever retriever = retriever(
                mock(AdaptiveScoringService.class),
                knowledgeBase(),
                mock(NeuralPathFormationService.class),
                transformer,
                handler);

        List<Content> out = retriever.retrieveStateDriven(
                PromptContext.builder()
                        .userQuery("QueryTransformer breaker fallback")
                        .lastAssistantAnswer("assistant draft")
                        .subject("QueryTransformer")
                        .build(),
                1);

        assertEquals(List.of("QueryTransformer breaker fallback"), handledQueries);
        assertFalse(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("breaker_open", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=secret"));
    }

    @Test
    void stateDrivenRetrievalFallsBackToRawQueryWhenTransformerReturnsBlankOnly() {
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transformEnhanced(anyString(), anyString(), anyString()))
                .thenReturn(List.of(" ", "\t"));

        List<String> handledQueries = new CopyOnWriteArrayList<>();
        RetrievalHandler handler = (query, accumulator) -> {
            handledQueries.add(query.text());
            accumulator.add(Content.from("Raw query fallback evidence"));
        };

        HybridRetriever retriever = retriever(
                mock(AdaptiveScoringService.class),
                knowledgeBase(),
                mock(NeuralPathFormationService.class),
                transformer,
                handler);

        List<Content> out = retriever.retrieveStateDriven(
                PromptContext.builder()
                        .userQuery("QueryTransformer blank fallback")
                        .lastAssistantAnswer("assistant draft")
                        .subject("QueryTransformer")
                        .build(),
                1);

        assertEquals(List.of("QueryTransformer blank fallback"), handledQueries);
        assertFalse(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("empty_result", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
    }

    @Test
    void simpleWebRetrievalPublishesHybridProviderSkipRollup() {
        WebSearchRetriever web = mock(WebSearchRetriever.class);
        when(web.retrieve(any(dev.langchain4j.rag.query.Query.class))).thenAnswer(invocation -> {
            TraceStore.put("web.brave.skipped", true);
            TraceStore.put("web.brave.skipped.reason", "missing_brave_api_key");
            return List.of();
        });
        QueryComplexityGate gate = mock(QueryComplexityGate.class);
        when(gate.assess(anyString())).thenReturn(QueryComplexityGate.Level.SIMPLE);
        LangChainRAGService rag = mock(LangChainRAGService.class);
        when(rag.asContentRetriever(any())).thenReturn(query -> List.of());

        HybridRetriever retriever = retriever(
                mock(AdaptiveScoringService.class),
                knowledgeBase(),
                mock(NeuralPathFormationService.class),
                mock(QueryTransformer.class),
                mock(RetrievalHandler.class),
                web,
                gate,
                rag);
        ReflectionTestUtils.setField(retriever, "topK", 3);

        List<Content> out = retriever.retrieve(new dev.langchain4j.rag.query.Query("provider skip rollup"));

        assertTrue(out.isEmpty());
        assertEquals(0, TraceStore.get("hybrid.web.outCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("hybrid.web.starvation"));
        assertEquals(Boolean.TRUE, TraceStore.get("hybrid.web.provider.brave.skipped"));
        assertEquals("missing_brave_api_key", TraceStore.get("hybrid.web.provider.brave.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("hybrid.web.provider.naver.skipped"));
        assertEquals("", TraceStore.get("hybrid.web.provider.naver.skipReason"));
    }

    private static KnowledgeBaseService knowledgeBase() {
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain("Alpha Beta")).thenReturn("GENERAL");
        Set<String> entities = new LinkedHashSet<>(List.of("Alpha", "Beta"));
        when(kb.findMentionedEntities("GENERAL", "Alpha Beta")).thenReturn(entities);
        return kb;
    }

    private static void invokeImplicitConsistency(HybridRetriever retriever) {
        ReflectionTestUtils.invokeMethod(
                retriever,
                "maybeRecordImplicitConsistency",
                "Alpha Beta",
                List.of(Content.from("Alpha and Beta \uc2dc\ub108\uc9c0 evidence")),
                List.of());
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
        assertTrue(method.contains("fail-soft stage={} errorType={}")
                        && method.contains("\"invalid_number\""),
                "numeric fallback parser should use stable invalid_number error label: " + signature);
    }

    @SuppressWarnings("unchecked")
    private static HybridRetriever retriever(AdaptiveScoringService scoring,
            KnowledgeBaseService kb,
            NeuralPathFormationService pathFormation) {
        return retriever(
                scoring,
                kb,
                pathFormation,
                mock(QueryTransformer.class),
                mock(RetrievalHandler.class));
    }

    @SuppressWarnings("unchecked")
    private static HybridRetriever retriever(AdaptiveScoringService scoring,
            KnowledgeBaseService kb,
            NeuralPathFormationService pathFormation,
            QueryTransformer transformer,
            RetrievalHandler handler) {
        return retriever(
                scoring,
                kb,
                pathFormation,
                transformer,
                handler,
                mock(WebSearchRetriever.class),
                mock(QueryComplexityGate.class),
                mock(LangChainRAGService.class));
    }

    @SuppressWarnings("unchecked")
    private static HybridRetriever retriever(AdaptiveScoringService scoring,
            KnowledgeBaseService kb,
            NeuralPathFormationService pathFormation,
            QueryTransformer transformer,
            RetrievalHandler handler,
            WebSearchRetriever webSearchRetriever,
            QueryComplexityGate gate,
            LangChainRAGService ragService) {
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        LightWeightRanker ranker = mock(LightWeightRanker.class);
        when(ranker.rank(anyList(), anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        RerankGate rerankGate = mock(RerankGate.class);
        when(rerankGate.shouldRerank(anyList())).thenReturn(false);
        RelevanceScoringService relevance = mock(RelevanceScoringService.class);
        when(relevance.relatedness(anyString(), anyString())).thenReturn(1.0d);
        ReciprocalRankFuser fuser = new ReciprocalRankFuser();
        return new HybridRetriever(
                ranker,
                rerankGate,
                mock(AuthorityScorer.class),
                handler,
                fuser,
                mock(AnswerQualityEvaluator.class),
                mock(SelfAskPlanner.class),
                relevance,
                mock(HyperparameterService.class),
                mock(ElementConstraintScorer.class),
                transformer,
                scoring,
                kb,
                pathFormation,
                mock(SelfAskWebSearchRetriever.class),
                mock(AnalyzeWebSearchRetriever.class),
                webSearchRetriever,
                gate,
                ragService,
                mock(EmbeddingModel.class),
                embeddingStore,
                mock(GameDomainDetector.class));
    }
}
