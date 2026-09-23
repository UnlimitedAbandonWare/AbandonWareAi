package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.search.terms.SelectedTermsDebug;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDiagnosticRedactionSourceGuardTest {

    @Test
    void highTrafficDiagnosticsUseQueryHashesInsteadOfRawQueryTemplates() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java"),
                Path.of("main/java/com/example/lms/search/provider/HybridWebSearchProvider.java"),
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                Path.of("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/ContextOrchestrator.java"),
                Path.of("main/java/com/example/lms/service/rag/guard/EvidenceGate.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/EntityDisambiguationHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/LocationAwareHandler.java"),
                Path.of("main/java/com/example/lms/service/trace/TraceHtmlBuilder.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);

            assertFalse(code.contains("query='{}'"), source + " logs raw query template");
            assertFalse(code.contains("q='{}'"), source + " logs raw q template");
            assertFalse(code.contains("Query: {}"), source + " logs raw query label");
            assertFalse(code.contains("TraceStore.put(\"queryPlanner.finalUsed\", finalQs)"),
                    source + " stores raw planner final queries");
            assertTrue(code.contains("queryHash") || code.contains("hash12") || code.contains("SafeRedactor"),
                    source + " should keep diagnostics hash-based");
        }
    }

    @Test
    void keywordSelectionDiagnosticsDoNotExposeRawSelectedTerms() throws Exception {
        Path planner = Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java");
        Path selector = Path.of("main/java/com/example/lms/search/KeywordSelectionService.java");
        String plannerCode = Files.readString(planner, StandardCharsets.UTF_8);
        String selectorCode = Files.readString(selector, StandardCharsets.UTF_8);

        assertFalse(plannerCode.contains("TraceStore.put(\"selectedTerms\", selected)"),
                planner + " stores raw selected terms in public TraceStore");
        assertFalse(plannerCode.contains("TraceStore.put(\"selectedTerms\", fallbackTerms)"),
                planner + " stores raw fallback terms in public TraceStore");
        assertFalse(selectorCode.contains("TraceStore.put(\"keywordSelection.fallback.must\", must)"),
                selector + " stores raw fallback MUST terms");
        assertFalse(selectorCode.contains("TraceStore.put(\"keywordSelection.fallback.should\", should)"),
                selector + " stores raw fallback SHOULD terms");
        assertFalse(selectorCode.contains("TraceStore.put(\"keywordSelection.fallback.entityPhrase\", entityPhrase)"),
                selector + " stores raw fallback entity phrase");
        assertFalse(selectorCode.contains("\"seed\", seedPreview"),
                selector + " stores raw fallback event seed");
        assertFalse(selectorCode.contains("\"anchor\", anchor"),
                selector + " stores raw fallback event anchor");
        assertFalse(selectorCode.contains("\"secondMust\", secondMust"),
                selector + " stores raw fallback event second MUST");
        assertFalse(selectorCode.contains("\"exact\", exact.isEmpty()"),
                selector + " stores raw fallback event exact phrase");

        String rawSentinel = "raw-private-keyword-selection-sentinel";
        SelectedTerms selected = SelectedTerms.builder()
                .exact(List.of(rawSentinel + "-exact"))
                .must(List.of(rawSentinel + "-must"))
                .should(List.of(rawSentinel + "-should"))
                .negative(List.of(rawSentinel + "-negative"))
                .aliases(List.of(rawSentinel + "-alias"))
                .domains(List.of("private.example.test"))
                .domainProfile(rawSentinel + "-domain-profile")
                .build();

        TraceStore.clear();
        try {
            TraceStore.put("selectedTerms", selected);
            TraceStore.put("web.selectedTerms", SelectedTermsDebug.toDebugMap(selected, 8));
            TraceStore.put("web.selectedTerms.summary", SelectedTermsDebug.toSummaryString(selected));

            String dump = String.valueOf(TraceStore.getAll());
            assertFalse(dump.contains(rawSentinel), dump);
            assertFalse(dump.contains("private.example.test"), dump);
            assertFalse(dump.contains(rawSentinel + "-domain-profile"), dump);
            assertTrue(dump.contains("hash:"), dump);
            assertTrue(dump.contains("web.selectedTerms.summary"), dump);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void keywordSelectionReasonDiagnosticsUseSafeMessages() throws Exception {
        Path planner = Path.of("main/java/com/example/lms/search/SmartQueryPlanner.java");
        Path selector = Path.of("main/java/com/example/lms/search/KeywordSelectionService.java");
        String plannerCode = Files.readString(planner, StandardCharsets.UTF_8);
        String selectorCode = Files.readString(selector, StandardCharsets.UTF_8);

        assertFalse(selectorCode.contains("TraceStore.put(\"aux.keywordSelection.forceMinMust.reason\", reason);"),
                selector + " stores raw force-min reason");
        assertFalse(selectorCode.contains(
                "TraceStore.put(\"aux.keywordSelection.forceMinMust.reason\", SafeRedactor.safeMessage(reason, 120));"),
                selector + " should not store free-form force-min reason messages");
        assertTrue(selectorCode.contains(
                "TraceStore.put(\"aux.keywordSelection.forceMinMust.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"),
                selector + " should store force-min reason labels");

        assertFalse(plannerCode.contains("TraceStore.put(\"keywordSelection.bypass.reason\", reason);"),
                planner + " stores raw bypass reason");
        assertFalse(plannerCode.contains("\"keywordSelection.mode=bypassed reason=\" + reason"),
                planner + " appends raw bypass reason");
        assertFalse(plannerCode.contains(
                "TraceStore.put(\"keywordSelection.bypass.reason\", SafeRedactor.safeMessage(reason, 120));"),
                planner + " should not store free-form bypass reason messages");
        assertFalse(plannerCode.contains(
                "\"keywordSelection.mode=bypassed reason=\" + SafeRedactor.safeMessage(reason, 120)"),
                planner + " should not append free-form bypass reason messages");
        assertTrue(plannerCode.contains(
                "TraceStore.put(\"keywordSelection.bypass.reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"),
                planner + " should store bypass reason labels");
        assertTrue(plannerCode.contains(
                "\"keywordSelection.mode=bypassed reason=\" + SafeRedactor.traceLabelOrFallback(reason, \"unknown\")"),
                planner + " should append bypass reason labels");
    }

    @Test
    void domainProfileDiagnosticsUseHashesInsteadOfRawValues() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/NaverPlanHintBoostOnlyOverlayAspect.java"),
                Path.of("main/java/ai/abandonware/nova/orch/aop/UawIdleAutoTrainingPipelineAspect.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);

            assertFalse(code.contains("TraceStore.put(\"keywordSelection.domainProfile\", dp)"),
                    source + " stores raw keyword-selection domainProfile");
            assertFalse(code.contains("TraceStore.put(\"uaw.pipeline.domainProfile\", domainProfile)"),
                    source + " stores raw UAW domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.naver.domainProfile\", hasDomainProfile ? domainProfile : null)"),
                    source + " stores raw Naver domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.domainProfile\", effectiveProfile)"),
                    source + " stores raw effective domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.naver.domainProfileDemoted.original\", domainProfileOriginal)"),
                    source + " stores raw demoted domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.naver.domainProfileDemoted.filterStage.original\", originalProfile)"),
                    source + " stores raw filter-stage domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.naver.filter.profile\", finalProfileUsable ? finalEffectiveProfile : null)"),
                    source + " stores raw filter-stage effective domainProfile");
            assertFalse(code.contains("TraceStore.put(\"web.naver.planHintBoostOnly.original.domainProfile\", original.getDomainProfile())"),
                    source + " stores raw overlay original domainProfile");
            assertTrue(code.contains("SafeRedactor.hashValue(") || code.contains("GENERAL->null"),
                    source + " should keep domainProfile diagnostics hash-based or constant-only");
        }
    }

    @Test
    void entityDisambiguationDiagnosticsDoNotExposeRawAnalysisText() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/EntityDisambiguationHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("domain={}, hints={}, noise={}"),
                source + " logs raw domain/hints/noise values");
        assertFalse(code.contains("analysis.expectedDomain(),"),
                source + " logs raw expectedDomain");
        assertFalse(code.contains("analysis.contextHints(),"),
                source + " logs raw contextHints");
        assertFalse(code.contains("analysis.noiseDomains(),"),
                source + " logs raw noiseDomains");
        assertFalse(code.contains("merged.put(\"originalQuery\", originalText);"),
                source + " stores raw original query metadata");
        assertFalse(code.contains("merged.put(\"expectedDomain\", analysis.expectedDomain());"),
                source + " stores raw expectedDomain metadata");
        assertFalse(code.contains("merged.put(\"noiseDomains\", String.join(\",\", analysis.noiseDomains()));"),
                source + " stores raw noiseDomains metadata");
        assertFalse(code.contains("merged.put(\"contextHints\", String.join(\",\", analysis.contextHints()));"),
                source + " stores raw contextHints metadata");
        assertTrue(code.contains("domainHash={}") && code.contains("originalQueryHash12"),
                source + " should expose only hash/count diagnostics for analysis text");
    }

    @Test
    void retrieverLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"))) {
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
    }

    @Test
    void searchSupportLogsDoNotUseRawThrowableMessagesOrTypoMapValues() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/search/KeyTermMiner.java"),
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                Path.of("main/java/com/example/lms/search/TypoNormalizer.java"),
                Path.of("main/java/com/example/lms/search/QueryExpander.java"))) {
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

        String typoNormalizer = Files.readString(
                Path.of("main/java/com/example/lms/search/TypoNormalizer.java"),
                StandardCharsets.UTF_8);
        assertFalse(typoNormalizer.contains("raw='{}'"));
        assertTrue(typoNormalizer.contains("rawHash={}") && typoNormalizer.contains("rawLength={}"));

        String queryExpander = Files.readString(
                Path.of("main/java/com/example/lms/search/QueryExpander.java"),
                StandardCharsets.UTF_8);
        assertFalse(queryExpander.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(queryExpander.contains("[QueryExpander] LLM suggestion generation failed. errorHash={} errorLength={}"));
        assertTrue(queryExpander.contains("[QueryExpander] sanitizer failed, using original lines. errorHash={} errorLength={}"));
        assertTrue(queryExpander.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));

        String keywordSelection = Files.readString(
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                StandardCharsets.UTF_8);
        assertFalse(keywordSelection.contains("SafeRedactor.safeMessage(String.valueOf(parseEx), 180)"));
        assertFalse(keywordSelection.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(keywordSelection.contains("[KeywordSelection] JSON parse failed, fall back to heuristics. errorHash={} errorLength={}"));
        assertTrue(keywordSelection.contains(
                "[KeywordSelection] unexpected failure in keyword selection, fall back to heuristics type={} errorHash={} errorLength={}"));
        assertTrue(keywordSelection.contains("SafeRedactor.hashValue(messageOf(parseEx)), messageLength(parseEx)"));
        assertTrue(keywordSelection.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void smallRagHandlersDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/rag/guard/MemoryAsEvidenceAdapter.java"),
                Path.of("main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/chain/ImagePromptGroundingHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/pre/CompositeQueryContextPreprocessor.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/FileHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/MemoryHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/LocationAwareHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/MemoryWriteInterceptor.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/SelfAskHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/VectorDbHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/WebSearchHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/handler/WebHandler.java"))) {
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
    }

    @Test
    void memoryAsEvidenceAdapterFailSoftLogsUseStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/guard/MemoryAsEvidenceAdapter.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("getSessionWithMessages failed: {}"));
        assertFalse(code.contains("KB evidence snippets failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][evidence] session memory load failed failureReason={} errorType={} sessionHash={}"));
        assertTrue(code.contains(
                "[AWX][rag][evidence] kb snippets load failed failureReason={} errorType={} domainHash={} subjectHash={}"));
        assertTrue(code.contains("\"memory-session-load-error\""));
        assertTrue(code.contains("\"memory-kb-snippets-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hashValue(String.valueOf(sessionId))"));
        assertTrue(code.contains("SafeRedactor.hashValue(domain)"));
        assertTrue(code.contains("SafeRedactor.hashValue(subject)"));
    }

    @Test
    void hybridRetrieverLogsDoNotUseRawThrowableMessages() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
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

    @Test
    void hybridRetrieverSelectionAndFallbackLogsUseHashAndLengthOnly() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains(
                "log.warn(\"[Hybrid] reranker backend selection failed; preserving injected default: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertFalse(code.contains(
                "log.warn(\"[HybridRetriever] Web search failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertFalse(code.contains(
                "log.debug(\"[Hybrid] Tavily fallback skipped: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180));"));
        assertTrue(code.contains(
                "log.warn(\"[Hybrid] reranker backend selection failed; preserving injected default errorHash={} errorLength={}\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
        assertTrue(code.contains(
                "log.warn(\"[HybridRetriever] Web search failed errorHash={} errorLength={}\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
        assertTrue(code.contains(
                "log.debug(\"[Hybrid] Tavily fallback skipped errorHash={} errorLength={}\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
    }

    @Test
    void hybridRetrieverProgressiveHandlerLogsUseHashAndLengthOnly() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("): {} -> fallback to raw query"));
        assertTrue(code.contains("): errorHash={} errorLength={} -> fallback to raw query"));
        assertFalse(code.lines().anyMatch(line -> line.contains("log.warn(\"[Hybrid] handler")
                && line.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)")));
        assertTrue(code.lines()
                .filter(line -> line.contains("log.warn(\"[Hybrid] handler"))
                .filter(line -> line.contains("errorHash={} errorLength={}"))
                .count() >= 2);
        assertFalse(code.contains(
                "log.error(\"[Hybrid] retrieveProgressive failed (sidHash={}, queryHash12={}, queryLength={}) err={}\""));
        assertTrue(code.contains(
                "log.error(\"[Hybrid] retrieveProgressive failed (sidHash={}, queryHash12={}, queryLength={}) errHash={} errLength={}\""));
    }

    @Test
    void hybridRetrieverRemainingFailureLogsUseHashAndLengthOnly() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.lines().anyMatch(line -> line.contains("retrieveAll")
                && line.contains("errorHash={} errorLength={}")));
        assertTrue(code.contains(
                "log.debug(\"[Hybrid] rerankGate errorHash={} errorLength={}; falling back to size check\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
        assertTrue(code.contains(
                "log.debug(\"[Hybrid] cross-encoder rerank skipped errorHash={} errorLength={}\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
        assertTrue(code.contains(
                "log.debug(\"[Hybrid] softmax calibration failed errorHash={} errorLength={}\", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());"));
    }

    @Test
    void langChainRagServiceLogsUseTypeLabelsInsteadOfThrowableMessages() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(e.getMessage()"));
        assertFalse(code.contains("SafeRedactor.safeMessage(ex.getMessage()"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains("[AWX][rag][vector] logVectorState failed failureReason={} errorType={}"));
        assertTrue(code.contains("[AWX][rag][vector] metadata filter failed failureReason={} errorType={} sidHash={}"));
        assertTrue(code.contains("[RAG] retrieveGlobalKbDomain failed (fail-soft). errorHash={} errorLength={}"));
        assertTrue(code.contains("[AWX][rag][vector] retrieve failed failureReason={} errorType={}"));
    }

    @Test
    void unifiedOrchestratorQueryAnalysisFailSoftLogUsesTypeLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Orchestrator] Query analysis failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(code.contains("[AWX][rag][orchestrator] query analysis failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(req.query)"));
    }

    @Test
    void unifiedOrchestratorRetrievalFailSoftLogsUseTypeLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Orchestrator] Web retrieval failed, fail-soft continuing: {}"));
        assertFalse(code.contains("[Orchestrator] Vector retrieval failed, fail-soft continuing: {}"));
        assertFalse(code.contains("[Orchestrator] KG retrieval failed, fail-soft continuing: {}"));
        assertTrue(code.contains("[AWX][rag][orchestrator] web retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("[AWX][rag][orchestrator] vector retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("[AWX][rag][orchestrator] kg retrieval failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"web-retrieval-error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("\"vector-retrieval-error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("\"kg-retrieval-error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void unifiedOrchestratorOfflineTextureWriteFailSoftUsesStableStatus() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("TraceStore.put(\"rag.offlineTexture.write.status\", \"fail_soft:\" + e.getClass().getSimpleName())"));
        assertTrue(code.contains("TraceStore.put(\"rag.offlineTexture.write.status\", \"fail_soft:offline_texture_write_failed\")"));
    }

    @Test
    void unifiedOrchestratorHelperRetrievalLogsUseTypeLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[UnifiedRagOrchestrator] Failed to retrieve from {}: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains("[AWX][rag][orchestrator] source retrieval failed failureReason={} errorType={} sourceTag={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"source-retrieval-error\", com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("com.example.lms.trace.SafeRedactor.traceLabelOrFallback(String.valueOf(sourceTag), \"unknown\")"));
    }

    @Test
    void compositePreprocessorFailSoftLogsUseStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/pre/CompositeQueryContextPreprocessor.java");
        String code = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertFalse(code.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"),
                source + " should not log exception message text for guardrail failures");
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"),
                source + " should not log throwable text for delegate failures");
        assertFalse(code.contains("[Guardrail] preprocessor failed, skipping for this request. cause={}"));
        assertFalse(code.contains("[QueryPreprocessor] delegate failed (fail-soft). preprocessor={} err={}"));
        assertTrue(code.contains(
                "[AWX][rag][preprocessor] guardrail failed failureReason={} errorType={} preprocessor={} queryHash12={} queryLength={}"));
        assertTrue(code.contains(
                "[AWX][rag][preprocessor] delegate failed failureReason={} errorType={} preprocessor={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"guardrail-preprocessor-error\""));
        assertTrue(code.contains("\"query-preprocessor-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(d.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(enriched)"));
    }

    @Test
    void webHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/WebHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Web] failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] web failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"web-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(q == null ? null : q.text())"));
    }

    @Test
    void analyzeHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/AnalyzeHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Analyze] failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] analyze failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"analyze-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(q == null ? null : q.text())"));
    }

    @Test
    void selfAskHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/SelfAskHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[SelfAsk] failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] selfAsk failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"selfask-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(q == null ? null : q.text())"));
    }

    @Test
    void vectorDbHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/VectorDbHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] vectorDb failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"vectordb-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(q == null ? null : q.text())"));
    }

    @Test
    void fileHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/FileHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[FileHandler] failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] file failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"file-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(query == null ? null : query.text())"));
    }

    @Test
    void locationAwareHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/LocationAwareHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[LocationAware] error {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] locationAware failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"locationaware-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(q == null ? null : q.text())"));
    }

    @Test
    void memoryHandlerFailSoftLogsUseStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/MemoryHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] memory loadForSession failed failureReason={} errorType={} sessionHash={}"));
        assertTrue(code.contains("\"memory-load-session-error\""));
        assertTrue(code.contains(
                "[AWX][rag][handler] memory loadRecoveryContents failed failureReason={} errorType={} sessionHash={} maxItems={}"));
        assertTrue(code.contains("\"memory-recovery-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(String.valueOf(sessionId))"));
        assertTrue(code.contains("TraceStore.put(\"memory.recovery.reason\","));
    }

    @Test
    void memoryWriteInterceptorFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/MemoryWriteInterceptor.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] memoryWrite reinforce failed failureReason={} errorType={} sessionHash={}"));
        assertTrue(code.contains("\"memory-write-reinforce-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(String.valueOf(sessionKey))"));
    }

    @Test
    void evidenceRepairHandlerFailSoftLogsUseStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/EvidenceRepairHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[CRAG][repair] fail-soft: {}"));
        assertFalse(code.contains("[Repair] retrieve failed; escalating to caller fail-soft boundary: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] evidenceRepair failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"evidence-repair-handler-error\""));
        assertTrue(code.contains(
                "[AWX][rag][handler] evidenceRepair retrieve failed failureReason={} errorType={} failureClass={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"evidence-repair-retrieve-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(classifyFailure(e), \"silent-failure\")"));
        assertTrue(code.contains("SafeRedactor.hash12(query == null ? null : query.text())"));
    }

    @Test
    void knowledgeGraphHandlerRetrieveFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/KnowledgeGraphHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[KnowledgeGraphHandler] retrieve failed; returning empty list: {}"));
        assertTrue(code.contains(
                "[AWX][rag][handler] kg retrieve failed failureReason={} errorType={} failureClass={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"kg-retrieve-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(classifyFailure(e), \"silent-failure\")"));
        assertTrue(code.contains("queryHash12"));
        assertTrue(code.contains("text == null ? 0 : text.length()"));
    }

    @Test
    void webSearchHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/handler/WebSearchHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][handler] webSearch failed failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("\"web-search-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(effectiveQuery == null ? null : effectiveQuery.text())"));
    }

    @Test
    void attachmentContextHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("AttachmentContextHandler failed, passing down: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][chain] attachmentContext failed failureReason={} errorType={} sessionHash={}"));
        assertTrue(code.contains("\"attachment-context-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(ctx == null ? null : ctx.sessionId())"));
    }

    @Test
    void imagePromptGroundingHandlerFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/chain/ImagePromptGroundingHandler.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("ImagePromptGroundingHandler failed, passing down: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains(
                "[AWX][rag][chain] imagePromptGrounding failed failureReason={} errorType={} sessionHash={}"));
        assertTrue(code.contains("\"image-prompt-grounding-handler-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(ctx == null ? null : ctx.sessionId())"));
    }

    @Test
    void jamminiBraveSearchAdapterFailSoftLogUsesStructuredLabels() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/adapter/JamminiBraveSearchAdapter.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Brave] searchSnippets failed: {}"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertTrue(code.contains(
                "[AWX][search][brave] jammini searchSnippets failed failureReason={} errorType={} queryHash12={} queryLength={} requestedCount={}"));
        assertTrue(code.contains("\"jammini-brave-search-error\""));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(code.contains("SafeRedactor.hash12(query)"));
        assertTrue(code.contains("query == null ? 0 : query.length()"));
    }

    @Test
    void hybridRetrieverRerankSkipReasonUsesSafeMessage() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/HybridRetriever.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("TraceStore.put(\"rerank.ce.skipReason\", reason);"));
        assertFalse(code.contains("TraceStore.put(\"rerank.ce.skipReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(code.contains("TraceStore.put(\"rerank.ce.skipReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void embeddingCrossEncoderRerankerLogsDoNotUseRawUrlsOrThrowableMessages() throws Exception {
        Path source = Path.of("main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java");
        String code = Files.readString(source, StandardCharsets.UTF_8);

        assertFalse(code.contains("[Rerank] url={} cred={} decay={}"));
        assertFalse(code.contains("Error={}\""));
        assertFalse(code.contains("e.getMessage()"));
        assertFalse(code.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(code.contains("[Rerank] urlHash={} cred={} decay={}"));
        assertTrue(code.contains("[AWX][rag][rerank] embedding fallback failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(code.contains("SafeRedactor.hashValue(url)"));
        assertTrue(code.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
    }
}
