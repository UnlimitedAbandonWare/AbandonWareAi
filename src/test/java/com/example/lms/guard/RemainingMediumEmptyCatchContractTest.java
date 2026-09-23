package com.example.lms.guard;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemainingMediumEmptyCatchContractTest {

    private static final Pattern SCORE_RUNNER_EMPTY_CATCH = Pattern.compile(
            "catch[ \\t]*\\([^\\r\\n)]*(Exception|Throwable|RuntimeException|IOException|NumberFormatException|"
                    + "IllegalStateException|DateTimeParseException)[^\\r\\n)]*\\)[ \\t]*\\{[ \\t]*(?://[^\\r\\n]*)?[ \\t]*\\}",
            Pattern.MULTILINE);

    @Test
    void remainingMediumRiskFilesDoNotUseExactEmptyCatchBlocks() throws Exception {
        List<String> paths = List.of(
                "main/java/ai/abandonware/nova/orch/web/WebFailSoftDomainStageReportService.java",
                "main/java/ai/abandonware/nova/orch/web/WebSnippet.java",
                "main/java/com/abandonware/ai/agent/session/SessionTtlFilter.java",
                "main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java",
                "main/java/com/example/lms/api/ApiSecurityExceptionAdvice.java",
                "main/java/com/example/lms/api/DebugEventsDiagnosticsController.java",
                "main/java/com/example/lms/health/GpuHardwareDiagnostics.java",
                "main/java/com/example/lms/orchestration/WorkflowOrchestrator.java",
                "main/java/com/example/lms/plugin/image/ImageGenerationPluginController.java",
                "main/java/com/example/lms/plugin/image/storage/FileSystemImageStorage.java",
                "main/java/com/example/lms/service/rag/AnswerQualityEvaluator.java",
                "main/java/com/example/lms/service/rag/LangChainRAGService.java",
                "main/java/com/example/lms/service/rag/kg/Neo4jKnowledgeGraphClient.java",
                "main/java/com/example/lms/service/rag/pre/CognitiveStateExtractor.java",
                "main/java/com/example/lms/service/rag/retriever/OcrRetriever.java",
                "main/java/com/example/lms/util/ProductAliasNormalizer.java");
        List<String> offenders = new ArrayList<>();

        for (String path : paths) {
            String source = Files.readString(Path.of(path));
            if (SCORE_RUNNER_EMPTY_CATCH.matcher(source).find()) {
                offenders.add(path);
            }
        }

        assertEquals(List.of(), offenders,
                "Remaining medium-risk fail-soft paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void guardProfileInvalidConfigFallbackLeavesFixedStageBreadcrumb() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/guard/GuardProfileProps.java"));

        assertTrue(source.contains("traceSuppressed(\"guardProfile.currentProfile\", e);"));
        assertTrue(source.contains("TraceStore.put(\"guard.profile.suppressed.\" + safeStage, true);"));
    }

    @Test
    void webSnippetHostParseUsesFixedInvalidUrlLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/web/WebSnippet.java"));

        assertTrue(source.contains("stage=url_host errorType=\" + errorType(ex)"));
        assertTrue(source.contains("return \"invalid_url\";"));
        assertTrue(!source.contains("ex.getClass().getSimpleName()"));
    }

    @Test
    void imagePromptHashLoggingFallbackLeavesRedactedBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/plugin/image/ImageGenerationPluginController.java"));

        assertTrue(source.contains("image.generate prompt hash logging skipped errorType={}"));
        assertTrue(source.contains("image.generate unexpected error skipped errorType={}"));
        assertTrue(source.contains("ignore.getClass().getSimpleName()"));
        assertTrue(source.contains("ex.getClass().getSimpleName()"));
    }

    @Test
    void utilityFallbacksLeaveFixedStageBreadcrumbs() throws Exception {
        String productAliases = Files.readString(Path.of("main/java/com/example/lms/util/ProductAliasNormalizer.java"));
        String relevanceScorer = Files.readString(Path.of("main/java/com/example/lms/util/RelevanceScorer.java"));
        String tokenCounter = Files.readString(Path.of("main/java/com/example/lms/util/TokenCounter.java"));

        assertTrue(productAliases.contains("Product alias properties load skipped resource="));
        assertTrue(relevanceScorer.contains("Relevance scoring embedding failed aLength={0} bLength={1}"));
        assertTrue(tokenCounter.contains("OpenAI compat usage token parse skipped"));
    }

    @Test
    void topCatchPressureParsersUseTypedValidationExceptions() throws Exception {
        String safeRedactor = Files.readString(Path.of("main/java/com/example/lms/trace/SafeRedactor.java"));
        String coherenceVerifier = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java"));
        String isoInstantParser = safeRedactor.substring(
                safeRedactor.indexOf("private static boolean isIsoInstant"),
                safeRedactor.indexOf("private static Map<String, Object> scalarSummary"));
        String normalizedEvidenceParser = coherenceVerifier.substring(
                coherenceVerifier.indexOf("private static NormalizedEvidence normalize"),
                coherenceVerifier.indexOf("private static String evidenceId"));

        assertTrue(isoInstantParser.contains("catch (DateTimeParseException ignored)"));
        assertTrue(!isoInstantParser.contains("catch (RuntimeException ignored)"));
        assertTrue(normalizedEvidenceParser.contains("catch (IllegalArgumentException | DateTimeException error)"));
        assertTrue(!normalizedEvidenceParser.contains("catch (RuntimeException error)"));
    }
}
