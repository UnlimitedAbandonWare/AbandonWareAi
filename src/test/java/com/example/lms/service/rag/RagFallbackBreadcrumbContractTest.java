package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.scoring.IsotonicCalibrator;
import com.example.lms.service.rag.rerank.RerankKnobResolver;
import com.example.lms.service.rag.query.QueryAnalysisResult.QueryIntent;
import com.example.lms.service.rag.query.QueryAnalysisService;
import com.example.lms.service.rag.safety.SafeRetrieveDecorator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagFallbackBreadcrumbContractTest {

    @Test
    void queryAndRewriteFallbacksExposeBreadcrumbs() throws Exception {
        String queryAnalysis = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/query/QueryAnalysisService.java"));
        String rewriteRisk = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/query/SelfAskRewriteRiskScorer.java"));

        assertTrue(queryAnalysis.contains("TraceStore.put(\"query.analysis.suppressed.intentParse\", true)"));
        assertTrue(queryAnalysis.contains("TraceStore.put(\"query.analysis.suppressed.stage\", \"intentParse\")"));
        assertTrue(queryAnalysis.contains("TraceStore.put(\"query.analysis.suppressed.errorType\", \"invalid_intent\")"));
        assertTrue(queryAnalysis.contains("TraceStore.put(\"query.analysis.suppressed.intentParse.errorType\""));
        assertTrue(rewriteRisk.contains("TraceStore.put(\"selfask.rewriteRisk.suppressed.toDouble\", true)"));
        assertTrue(rewriteRisk.contains("TraceStore.put(\"selfask.rewriteRisk.suppressed.stage\", \"toDouble\")"));
        assertTrue(rewriteRisk.contains("TraceStore.put(\"selfask.rewriteRisk.suppressed.errorType\", \"invalid_number\")"));
        assertFalse(rewriteRisk.contains("log.warn"));
    }

    @Test
    void queryIntentParseFallbackUsesStableInvalidIntentLabel() throws Exception {
        TraceStore.clear();
        Method parseIntent = QueryAnalysisService.class.getDeclaredMethod("parseIntent", String.class);
        parseIntent.setAccessible(true);

        QueryIntent intent = (QueryIntent) parseIntent.invoke(new QueryAnalysisService(), "not-a-real-intent");

        assertEquals(QueryIntent.GENERAL, intent);
        assertEquals(Boolean.TRUE, TraceStore.get("query.analysis.suppressed.intentParse"));
        assertEquals("invalid_intent", TraceStore.get("query.analysis.suppressed.intentParse.errorType"));
        assertEquals("intentParse", TraceStore.get("query.analysis.suppressed.stage"));
        assertEquals("invalid_intent", TraceStore.get("query.analysis.suppressed.errorType"));
    }

    @Test
    void rerankFallbacksExposeBreadcrumbs() throws Exception {
        String elementConstraint = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/rerank/ElementConstraintScorer.java"));
        String knobResolver = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/rerank/RerankKnobResolver.java"));

        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.textSegmentMethod\", true)"));
        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.stage\", \"textSegmentMethod\")"));
        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.errorType\""));
        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.textSegmentMethod.errorType\""));
        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.textExtract\", true)"));
        assertTrue(elementConstraint.contains("TraceStore.put(\"rerank.elementConstraint.suppressed.textExtract.errorType\""));
        assertTrue(knobResolver.contains("TraceStore.put(\"rerank.knob.suppressed.intParse\", true)"));
        assertTrue(knobResolver.contains("TraceStore.put(\"rerank.knob.suppressed.stage\", \"intParse\")"));
        assertTrue(knobResolver.contains("TraceStore.put(\"rerank.knob.suppressed.errorType\", \"invalid_number\")"));
    }

    @Test
    void rerankKnobNumericFallbackUsesStableInvalidNumberLabel() {
        TraceStore.clear();

        RerankKnobResolver.Resolved resolved = RerankKnobResolver.resolve(Map.of("rerank.topK", "not-a-number"));

        assertEquals(null, resolved.topK());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.knob.suppressed.intParse"));
        assertEquals("invalid_number", TraceStore.get("rerank.knob.suppressed.intParse.errorType"));
        assertEquals("intParse", TraceStore.get("rerank.knob.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rerank.knob.suppressed.errorType"));
    }

    @Test
    void rerankKnobDropsNonFiniteNumericValues() {
        TraceStore.clear();

        RerankKnobResolver.Resolved resolved = RerankKnobResolver.resolve(Map.of(
                "rerank.topK", Double.POSITIVE_INFINITY,
                "rerank.ce.topK", Double.NaN,
                "onnx.enabled", Double.NEGATIVE_INFINITY));

        assertEquals(null, resolved.topK());
        assertEquals(null, resolved.ceTopK());
        assertEquals(null, resolved.onnxEnabled());
        assertEquals(Boolean.TRUE, TraceStore.get("rerank.knob.suppressed.intParse"));
        assertEquals("invalid_number", TraceStore.get("rerank.knob.suppressed.intParse.errorType"));
        assertEquals("intParse", TraceStore.get("rerank.knob.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rerank.knob.suppressed.errorType"));
    }

    @Test
    void retrieverAndSafetyFallbacksExposeBreadcrumbs() throws Exception {
        String ocr = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/retriever/OcrRetriever.java"));
        String safeRetrieve = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/safety/SafeRetrieveDecorator.java"));

        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.urlMeta\", true)"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.stage\", \"urlMeta\")"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.stage\", \"dependencyTrace\")"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.stage\", \"faultMask\")"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.stage\", \"debugEvent\")"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.errorType\""));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.urlMeta.errorType\""));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.dependencyTrace\", true)"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.dependencyTrace.errorType\""));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.faultMask\", true)"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.faultMask.errorType\""));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.debugEvent\", true)"));
        assertTrue(ocr.contains("TraceStore.put(\"ocr.suppressed.debugEvent.errorType\""));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.delegate\", true)"));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.stage\", \"delegate\")"));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.stage\", \"errorHandler\")"));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.errorType\""));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.delegate.errorType\""));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.errorHandler\", true)"));
        assertTrue(safeRetrieve.contains("TraceStore.put(\"safeRetrieve.suppressed.errorHandler.errorType\""));
    }

    @Test
    void safeRetrieveFallbacksExposeStableErrorTypes() {
        TraceStore.clear();
        SafeRetrieveDecorator<String> decorator = new SafeRetrieveDecorator<>(
                null,
                false,
                0,
                (cacheKey, ex) -> {
                    throw new IllegalStateException("handler boom");
                });

        List<String> result = decorator.retrieve("cache-key", () -> {
            throw new IllegalArgumentException("delegate boom");
        });

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("safeRetrieve.suppressed.delegate"));
        assertEquals("IllegalArgumentException", TraceStore.get("safeRetrieve.suppressed.delegate.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("safeRetrieve.suppressed.errorHandler"));
        assertEquals("IllegalStateException", TraceStore.get("safeRetrieve.suppressed.errorHandler.errorType"));
        assertEquals("errorHandler", TraceStore.get("safeRetrieve.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("safeRetrieve.suppressed.errorType"));
    }

    @Test
    void scoringCacheAndReinforcementFallbacksExposeBreadcrumbs() throws Exception {
        String isotonic = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/scoring/IsotonicCalibrator.java"));
        String redis = Files.readString(Path.of(
                "main/java/com/example/lms/service/redis/RedisCooldownService.java"));
        String snippetPruner = Files.readString(Path.of(
                "main/java/com/example/lms/service/reinforcement/SnippetPruner.java"));

        assertTrue(isotonic.contains("TraceStore.put(\"calibration.isotonic.suppressed.load\", true)"));
        assertTrue(isotonic.contains("TraceStore.put(\"calibration.isotonic.suppressed.stage\", \"load\")"));
        assertTrue(isotonic.contains("TraceStore.put(\"calibration.isotonic.suppressed.errorType\", errorType)"));
        assertTrue(redis.contains("TraceStore.put(\"redis.cooldown.suppressed.close\", true)"));
        assertTrue(redis.contains("TraceStore.put(\"redis.cooldown.suppressed.close.errorType\""));
        assertTrue(snippetPruner.contains("TraceStore.put(\"snippetPruner.htmlStripFallback\", true)"));
        assertTrue(snippetPruner.contains("TraceStore.put(\"snippetPruner.htmlStripFallback.errorType\""));
    }

    @Test
    void isotonicLoadParseFallbackUsesStableInvalidNumberLabel() throws Exception {
        TraceStore.clear();
        String raw = "ownerToken=secret-not-a-number";
        Path file = Files.createTempFile("isotonic-invalid", ".tsv");
        try {
            Files.writeString(file, raw + "\t0.5\n");

            IsotonicCalibrator.load(file.toFile());

            assertEquals(Boolean.TRUE, TraceStore.get("calibration.isotonic.suppressed.load"));
            assertEquals("invalid_number", TraceStore.get("calibration.isotonic.suppressed.load.errorType"));
            assertEquals("load", TraceStore.get("calibration.isotonic.suppressed.stage"));
            assertEquals("invalid_number", TraceStore.get("calibration.isotonic.suppressed.errorType"));
            assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));
        } finally {
            Files.deleteIfExists(file);
            TraceStore.clear();
        }
    }
}
