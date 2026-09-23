package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RemainingSmallRedactionContractTest {

    @Test
    void remainingSmallFailSoftLogsDoNotUseRawThrowableMessagesOrCatalogPaths() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/chat/interceptor/UnderstandAndMemorizeInterceptor.java"),
                Path.of("main/java/com/example/lms/service/rag/adapter/JamminiBraveSearchAdapter.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java"),
                Path.of("main/java/com/acme/aicore/adapters/search/NaverSearchProvider.java"),
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java"),
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexHolder.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java"),
                Path.of("main/java/com/example/lms/config/alias/NineTileAliasCorrector.java"),
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                Path.of("main/java/com/example/lms/service/ModelSyncService.java"),
                Path.of("main/java/com/example/lms/service/TrainingService.java"),
                Path.of("main/java/com/example/lms/service/onnx/OnnxRuntimeService.java"),
                Path.of("main/java/com/example/lms/plugin/image/OpenAiImageService.java"),
                Path.of("main/java/com/example/lms/service/rag/catalog/ConceptCatalogLoader.java"),
                Path.of("main/java/com/example/lms/service/rag/catalog/OrgCatalogLoader.java"),
                Path.of("main/java/com/example/lms/service/rag/guard/UniversalDomainSanitizer.java"),
                Path.of("main/java/com/example/lms/service/rag/knowledge/UniversalLoreRegistry.java"),
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java"),
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"),
                Path.of("main/java/com/example/lms/service/rag/plan/PlanDslLoader.java"),
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java"),
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"),
                Path.of("main/java/com/example/lms/service/rag/HybridReranker.java"),
                Path.of("main/java/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java"),
                Path.of("main/java/com/example/lms/debug/AblationPenaltyBootDumper.java"),
                Path.of("main/java/com/example/lms/integrations/n8n/N8nNotifier.java"),
                Path.of("main/java/com/example/lms/learning/gemini/LearningWriteInterceptor.java"),
                Path.of("main/java/com/example/lms/infra/debug/ClasspathOriginReporter.java"),
                Path.of("main/java/com/example/lms/moe/RgbResourceProbe.java"),
                Path.of("main/java/com/example/lms/moe/RgbSoakReportService.java"),
                Path.of("main/java/com/example/lms/service/soak/runner/SoakQuickRunner.java"),
                Path.of("main/java/com/example/lms/util/HashUtil.java"),
                Path.of("main/java/com/example/lms/storage/LocalFileStorageService.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()")
                            || line.contains(".toString()")
                            || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }

        String conceptLoader = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/catalog/ConceptCatalogLoader.java"),
                StandardCharsets.UTF_8);
        String orgLoader = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/catalog/OrgCatalogLoader.java"),
                StandardCharsets.UTF_8);
        assertFalse(conceptLoader.contains("catalog {}: {}"));
        assertFalse(orgLoader.contains("catalog {}: {}"));
        assertFalse(conceptLoader.contains("concept entries from {}"));
        assertFalse(orgLoader.contains("organisation entries from {}"));
        assertTrue(conceptLoader.contains("catalogHash={}"));
        assertTrue(orgLoader.contains("catalogHash={}"));
        assertTrue(conceptLoader.contains("catalogLength={}"));
        assertTrue(orgLoader.contains("catalogLength={}"));

        String bm25Service = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java"),
                StandardCharsets.UTF_8);
        String bm25Holder = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexHolder.java"),
                StandardCharsets.UTF_8);
        assertFalse(bm25Service.contains("Rebuilding BM25 index at {} (maxDocs={})\", path, maxDocs"));
        assertTrue(bm25Service.contains("Rebuilding BM25 index pathHash={} pathLength={} (maxDocs={})"));
        assertTrue(bm25Service.contains("SafeRedactor.hashValue(String.valueOf(path))"));
        assertFalse(bm25Holder.contains("\"Failed to init BM25 index at \" + indexPath"));
        assertTrue(bm25Holder.contains("\"Failed to init BM25 index pathHash=\""));

        String cfvmStore = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java"),
                StandardCharsets.UTF_8);
        String soakQuickRunner = Files.readString(
                Path.of("main/java/com/example/lms/service/soak/runner/SoakQuickRunner.java"),
                StandardCharsets.UTF_8);
        assertFalse(cfvmStore.contains("tiles from {}"));
        assertTrue(cfvmStore.contains("bandit store loaded: {} tiles pathHash={} pathLength={}"));
        assertFalse(soakQuickRunner.contains("exitCode={} out={}"));
        assertTrue(soakQuickRunner.contains("exitCode={} outHash={} outLength={}"));
    }

    @Test
    void modelSyncFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ModelSyncService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[ModelSync] model sync failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void smallAdapterCatalogAndRerankerFailuresUseHashAndLengthOnly() throws Exception {
        String naver = Files.readString(
                Path.of("main/java/com/acme/aicore/adapters/search/NaverSearchProvider.java"),
                StandardCharsets.UTF_8);
        String conceptLoader = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/catalog/ConceptCatalogLoader.java"),
                StandardCharsets.UTF_8);
        String orgLoader = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/catalog/OrgCatalogLoader.java"),
                StandardCharsets.UTF_8);
        String hybridReranker = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/HybridReranker.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(naver, conceptLoader, orgLoader, hybridReranker)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        }
        assertTrue(naver.contains("NaverSearchProvider failed. errorHash={} errorLength={}{}"));
        assertTrue(conceptLoader.contains("[ConceptCatalogLoader] Failed to load catalog catalogHash={} catalogLength={} errorHash={} errorLength={}"));
        assertTrue(orgLoader.contains("[OrgCatalogLoader] Failed to load catalog catalogHash={} catalogLength={} errorHash={} errorLength={}"));
        assertTrue(hybridReranker.contains("Smart rerank failed, falling back to lexical reranker. errorHash={} errorLength={}"));
        assertTrue(naver.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
        assertTrue(conceptLoader.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(orgLoader.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(hybridReranker.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void ragOrchestratorFacadeFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("primary fallback to legacy. errorHash={} errorLength={}"));
        assertTrue(source.contains("RAG capture hook skipped. errorHash={} errorLength={}"));
        assertTrue(source.contains("shadow comparison failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void ragGraphExecutorSnapshotLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("snapshot skipped node={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void ragSmallFailSoftDiagnosticsUseHashAndLengthOnly() throws Exception {
        String anger = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/overdrive/AngerOverdriveNarrower.java"),
                StandardCharsets.UTF_8);
        String extremeZ = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/burst/ExtremeZTrigger.java"),
                StandardCharsets.UTF_8);
        String lore = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/knowledge/UniversalLoreRegistry.java"),
                StandardCharsets.UTF_8);
        String plan = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/plan/PlanDslLoader.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(anger, extremeZ, lore, plan)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        }
        assertTrue(anger.contains("CrossEncoder rerank failed, keeping original candidates. errorHash={} errorLength={}"));
        assertTrue(extremeZ.contains("extremez.trigger.contradictionScorerErrorHash"));
        assertTrue(extremeZ.contains("extremez.trigger.contradictionScorerErrorLength"));
        assertTrue(lore.contains("lore catalog load failed catalogHash={} catalogNameLength={} errorHash={} errorLength={}"));
        assertTrue(lore.contains("lore catalog scan failed. errorHash={} errorLength={}"));
        assertTrue(plan.contains("PlanDslLoader: failed to load pipeline plan planHash={} planLength={} errorHash={} errorLength={}"));
        for (String source : List.of(anger, extremeZ, lore, plan)) {
            assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
            assertTrue(source.contains("messageLength(e)"));
        }
    }

    @Test
    void extremeZSystemHandlerTraceErrorsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 120)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e.getCause() == null ? e : e.getCause()), 120)"));
        assertTrue(source.contains("traceError(\"extremez.execute.vectorRetrieverError\", e);"));
        assertTrue(source.contains("traceError(\"extremez.execute.parallel.taskError\", e.getCause() == null ? e : e.getCause());"));
        assertTrue(source.contains("traceError(\"extremez.execute.plannerError\", e);"));
        assertTrue(source.contains("traceError(\"extremez.execute.webError\", e);"));
        assertTrue(source.contains("traceError(\"extremez.execute.vectorError\", e);"));
        assertTrue(source.contains("traceError(\"extremez.execute.rrfError\", e);"));
        assertTrue(source.contains("TraceStore.put(key + \"Hash\", SafeRedactor.hashValue(messageOf(error)));"));
        assertTrue(source.contains("TraceStore.put(key + \"Length\", messageLength(error));"));
    }

    @Test
    void dynamicRetrievalOrderFallbackLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("orderService.decideOrder failed; fallback order used. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void cfvmLearningFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String aspect = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"),
                StandardCharsets.UTF_8);
        String store = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(aspect, store)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
            assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        }
        assertTrue(aspect.contains("feedback failed-soft. errorHash={} errorLength={}"));
        assertTrue(store.contains("bandit store load failed-soft. errorHash={} errorLength={}"));
        assertTrue(store.contains("bandit store flush failed-soft. errorHash={} errorLength={}"));
    }

    @Test
    void cfvmKallocLearningAspectDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "CFVM learning fail-soft paths need fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void bm25FailSoftLogsUseHashAndLengthOnly() throws Exception {
        String service = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexService.java"),
                StandardCharsets.UTF_8);
        String holder = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25IndexHolder.java"),
                StandardCharsets.UTF_8);

        assertFalse(service.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(holder.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(service.contains("BM25 autoIndex skipped. errorHash={} errorLength={}"));
        assertTrue(holder.contains("BM25 index refresh failed. errorHash={} errorLength={}"));
        assertTrue(service.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(holder.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertFalse(holder.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "BM25 holder close/refresh paths need fixed-stage breadcrumbs instead of exact empty catches");
    }

    @Test
    void hashUtilDigestInitializationLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/util/HashUtil.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void universalDomainSanitizerLoreLookupLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/guard/UniversalDomainSanitizer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("lore lookup failed domainHash={} domainLength={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void localFileStorageFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/storage/LocalFileStorageService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("파일 저장 실패. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void rgbResourceProbeGeminiKeyConflictLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbResourceProbe.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[RGB] gemini key conflict. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void rgbSoakReportWriteFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbSoakReportService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[RGB] failed to write report file. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void remainingOperationalNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/moe/RgbSoakReportService.java"),
                "private static int intMetric");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/telemetry/MatrixTelemetryExtractor.java"),
                "private static double nz");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/orchestration/ExecutionPlanApplier.java"),
                "private static double traceDouble");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/VectorQualityGuard.java"),
                "private static int safeInt");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/VectorQualityGuard.java"),
                "private static double safeDouble");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/SelfAskPlanner.java"),
                "private static long parseLong");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/rerank/RerankKnobResolver.java"),
                "private static Integer parseInt");
        assertSourceParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/ops/RagOpsLedgerService.java"),
                "private static double asDouble");
    }

    @Test
    void ablationPenaltyBootDumperFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/debug/AblationPenaltyBootDumper.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("[AblationPenalty] dump skipped errorHash={} errorLength={}{}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t), LogCorrelation.suffix()"));
    }

    @Test
    void classpathOriginReporterErrorLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/debug/ClasspathOriginReporter.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("<dup-scan-error errorHash={} errorLength={}>"));
        assertTrue(source.contains("<error errorHash={} errorLength={}>"));
        assertTrue(source.contains("stereotype {} -> <error errorHash={} errorLength={}>"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void typoNormalizerFailureLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/TypoNormalizer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(source.contains("Typo map bind failed (tree). Proceeding with fallbacks. errorHash={} errorLength={}"));
        assertTrue(source.contains("Typo map parse failed (rawHash={} rawLength={}). errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void keyTermMinerFailureLogUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/KeyTermMiner.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[KeyTermMiner] failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void learningWriteInterceptorFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/gemini/LearningWriteInterceptor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("Learning ingestion failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("Memory reinforcement failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void understandAndMemorizeFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/chat/interceptor/UnderstandAndMemorizeInterceptor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(source.contains("memory store failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SSE emit failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("summarization failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void trainingFailureJobMessageUsesHashOnlyErrorSummary() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/TrainingService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("job.setMessage(e.getMessage());"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("log.error(\"Training failed. errorHash={} errorLength={}\", errorHash(e), errorLength(e));"));
        assertTrue(source.contains("job.setMessage(\"Training failed. \" + errorSummary(e));"));
    }

    @Test
    void snippetPrunerFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String pruner = Files.readString(
                Path.of("main/java/com/example/lms/service/reinforcement/SnippetPruner.java"),
                StandardCharsets.UTF_8);

        assertFalse(pruner.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(pruner.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(pruner.contains("[Pruner] embed pruning failed. errorHash={} errorLength={}"));
        assertTrue(pruner.contains("TraceStore.put(\"snippetPruner.injectionStripFallback\", true);"));
        assertTrue(pruner.contains("TraceStore.put(\"snippetPruner.injectionStripFallback.errorType\", safeExceptionName(e));"));
        assertTrue(pruner.contains("TraceStore.put(\"snippetPruner.llmFallback\", true);"));
        assertTrue(pruner.contains("TraceStore.put(\"snippetPruner.llmFallback.errorType\", safeExceptionName(e));"));
    }

    @Test
    void nineTileAliasCorrectorThumbnailRewriteFailureUsesHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/alias/NineTileAliasCorrector.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains(
                "NineTileAliasCorrector: mini LLM thumbnail rewrite failed, fallback to rule-based only. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void nineTileAliasCorrectorThumbnailRewriteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/alias/NineTileAliasCorrector.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains(".rewrite(prompt)"));
        assertTrue(source.contains("String thumbnailRewritePrompt = buildThumbnailPrompt(trimmed, locale, context);"));
        assertTrue(source.contains(".rewrite(thumbnailRewritePrompt)"));
    }

    @Test
    void evidenceAwareGuardEscalationDiagnosticsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("evt.put(\"model\", escalatedName)"));
        assertFalse(source.contains("model={}\""));
        assertFalse(source.contains("TraceStore.put(\"guard.escalation.model\", escalatedName)"));
        assertTrue(source.contains("evt.put(\"modelHash\", com.example.lms.trace.SafeRedactor.hashValue(escalatedName))"));
        assertTrue(source.contains("evt.put(\"modelLength\", escalatedName == null ? 0 : escalatedName.length())"));
        assertTrue(source.contains("TraceStore.put(\"guard.escalation.modelHash\", com.example.lms.trace.SafeRedactor.hashValue(escalatedName))"));
        assertTrue(source.contains("TraceStore.put(\"guard.escalation.modelLength\", escalatedName == null ? 0 : escalatedName.length())"));
        assertTrue(source.contains("modelHash={} modelLength={}"));
    }

    @Test
    void evidenceAwareGuardDegradeReasonDiagnosticsUseSafeMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"guard.final.action.reason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"guard.degrade.reason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"guard.final.action.reason\", com.example.lms.trace.SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains(
                "TraceStore.put(\"guard.degrade.reason\", com.example.lms.trace.SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"guard.final.action.reason\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains(
                "TraceStore.put(\"guard.degrade.reason\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertFalse(source.contains(
                "return com.example.lms.trace.SafeRedactor.safeMessage(reason, 120);"));
        assertTrue(source.contains(
                "return com.example.lms.trace.SafeRedactor.traceLabelOrFallback(reason, \"constitutional_scorecard_block\");"));
    }

    @Test
    void evidenceAwareGuardEscalationReasonDiagnosticsUseTraceLabels() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/guard/EvidenceAwareGuard.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"guard.escalation.reason\", escalationReason);"));
        assertFalse(source.contains("evt.put(\"reason\", escalationReason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"guard.escalation.reason\", com.example.lms.trace.SafeRedactor.safeMessage(escalationReason, 120));"));
        assertFalse(source.contains(
                "evt.put(\"reason\", com.example.lms.trace.SafeRedactor.safeMessage(escalationReason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"guard.escalation.reason\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(escalationReason, \"unknown\"));"));
        assertTrue(source.contains(
                "evt.put(\"reason\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(escalationReason, \"unknown\"));"));
    }

    @Test
    void promptDebugLoggerSummarizesPromptAndResponseBodies() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/debug/PromptDebugLogger.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.debug(\"Prompt built:\\n{}\", msg);"));
        assertFalse(source.contains("log.debug(\"Response from model {}:\\n{}\", model, msg);"));
        assertTrue(source.contains("SafeRedactor.diagnosticValue(\"prompt\", msg)"));
        assertTrue(source.contains("SafeRedactor.diagnosticValue(\"response\", msg)"));
        assertTrue(source.contains("modelHash={} modelLength={}"));
        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "PromptDebugLogger config parsing needs fixed-stage breadcrumbs instead of exact empty catches");
        assertParserCatchNarrowed(source, "private String truncate(String text)");

        java.lang.reflect.Method errorType = com.example.lms.debug.PromptDebugLogger.class.getDeclaredMethod(
                "errorType", Throwable.class);
        errorType.setAccessible(true);
        assertEquals("invalid_number", errorType.invoke(null, new NumberFormatException("private-token")));
    }

    @Test
    void legacyGptAndModelCapabilityLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String gpt = Files.readString(
                Path.of("main/java/com/example/lms/service/GPTService.java"),
                StandardCharsets.UTF_8);
        String capabilities = Files.readString(
                Path.of("main/java/com/example/lms/llm/ModelCapabilities.java"),
                StandardCharsets.UTF_8);

        assertFalse(gpt.contains("Requesting completion from model: '{}'"));
        assertFalse(gpt.contains("Received response from model: '{}'"));
        assertTrue(gpt.contains("modelHash={} modelLength={}"));
        assertTrue(gpt.contains("SafeRedactor.hashValue(modelToUse)"));
        assertTrue(gpt.contains("SafeRedactor.hashValue(responseModel)"));
        assertTrue(gpt.contains("traceSuppressed(\"openai.chatCompletion\", wcre);"));
        assertTrue(gpt.contains("SafeRedactor.traceLabelOrFallback(stage, \"unknown\")"));

        assertFalse(capabilities.contains("rigid-temp model '{}'"));
        assertTrue(capabilities.contains("rigid-temp modelHash={} modelLength={}"));
        assertTrue(capabilities.contains("SafeRedactor.hashValue(modelId)"));
    }

    @Test
    void classpathOriginReporterSummarizesResourceUrls() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/debug/ClasspathOriginReporter.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("DUPLICATE {} -> {}"));
        assertFalse(source.contains("unique {} -> {}"));
        assertFalse(source.contains("log.info(\"[ClasspathOrigin] {} -> {}\", label, (url != null ? url : \"<null>\"));"));
        assertTrue(source.contains("urlHash={} urlLength={}"));
        assertTrue(source.contains("duplicateCount={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(url))"));
    }

    @Test
    void mpAwareRankingLogsMdcIdentifiersAsHashes() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/acme/aicore/adapters/ranking/MpAwareWeightedRrfRanking.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("sessionId={} xrid={}"));
        assertTrue(source.contains("sessionHash={} xridHash={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(sid)"));
        assertTrue(source.contains("SafeRedactor.hashValue(xrid)"));
        assertTrue(source.contains("import com.example.lms.search.TraceStore;"));
        assertTrue(source.contains("traceSuppressed(\"statsFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"rrfKFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"windowFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"weightFallback\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"labelLogFallback\", ex);"));
        assertTrue(source.contains("\"ranking.mpAware.\" + stage"));
        assertFalse(source.contains("catch (Throwable ignore)"));
        assertFalse(source.contains("catch (Exception ignore) {}"));
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
    }

    private static void assertSourceParserCatchNarrowed(Path path, String signature) throws Exception {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        assertParserCatchNarrowed(source, signature);
    }
}
