package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvidenceFailSoftBreadcrumbTest {

    @Test
    void evidencePlannerAndNumberFallbacksLeaveStageBreadcrumbs() throws Exception {
        String planner = read("main/java/com/example/lms/service/rag/LogicRagPlanner.java");
        String classifier = read("main/java/com/example/lms/service/rag/ModelBasedQueryComplexityClassifier.java");
        String numbers = read("main/java/com/example/lms/service/rag/SelfAskNumbers.java");
        String evidence = read("main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java");

        assertStage(planner, "LogicRagPlanner", "plan");
        assertStage(classifier, "ModelBasedQueryComplexityClassifier", "classify.predict");
        assertStage(classifier, "ModelBasedQueryComplexityClassifier", "translator.processInput");
        assertInvalidNumberStage(numbers, "SelfAskNumbers", "parseDouble");
        assertInvalidNumberStage(numbers, "SelfAskNumbers", "parseLong");
        assertStage(evidence, "RagEvidenceAttributionService", "promotion.gate");
        assertStage(evidence, "RagEvidenceAttributionService", "appendix.markerMismatchTrace");
        assertStage(evidence, "RagEvidenceAttributionService", "appendix.trace");
        assertStage(evidence, "RagEvidenceAttributionService", "document.text");
        assertStage(evidence, "RagEvidenceAttributionService", "document.metadata");
        assertStage(evidence, "RagEvidenceAttributionService", "promotion.trace");
        assertStage(evidence, "RagEvidenceAttributionService", "promotion.ragEvent");
        assertStage(evidence, "RagEvidenceAttributionService", "promotion.debugEvent");
        assertStage(evidence, "RagEvidenceAttributionService", "effectiveMinCitations");
        assertStage(evidence, "RagEvidenceAttributionService", "hostOf.parse");
        assertStage(evidence, "RagEvidenceAttributionService", "content.text");
        assertStage(evidence, "RagEvidenceAttributionService", "content.metadata");
        assertStage(evidence, "RagEvidenceAttributionService", "toInt");
        assertStage(evidence, "RagEvidenceAttributionService", "toDouble");
        assertStage(evidence, "RagEvidenceAttributionService", "sanitizePublicUrl");
    }

    @Test
    void ragEvidenceTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        RagEvidenceTraceSuppressions.trace("kindRank.parse", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("rag.evidence.suppressed.kindRank.parse"));
        assertEquals("invalid_number", TraceStore.get("rag.evidence.suppressed.kindRank.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void ragEvidenceTraceSuppressionsIncludeSafeAggregateStageAndErrorType() {
        TraceStore.clear();
        String rawStage = "kindRank.parse " + com.example.lms.test.SecretFixtures.openAiKey();

        RagEvidenceTraceSuppressions.trace(
                rawStage,
                new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("rag.evidence.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("rag.evidence.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("rag.evidence.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("rag.evidence.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void assertStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }

    private static void assertInvalidNumberStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing " + component + " invalid_number fail-soft stage: " + stage);
    }
}
