package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class OperationalQualitySourceContractTest {

    @Test
    void chatWorkflowReinforcementFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private void reinforce(");
        int end = source.indexOf("private String decideFinalQuery", start);
        assertTrue(start >= 0 && end > start, "reinforce() method span should be locatable");
        String reinforce = source.substring(start, end);

        assertTrue(reinforce.contains("TraceStore.inc(\"memory.reinforce.failed\")"),
                "reinforce() must leave a TraceStore breadcrumb when memory reinforcement fails");
        assertTrue(reinforce.contains("SafeRedactor.hashValue(sessionKey)"),
                "reinforce() must hash the session key before logging");
        assertTrue(reinforce.contains("memory reinforcement failed sessionHash={}")
                && reinforce.contains("t.getClass().getSimpleName()"),
                "reinforce() should log only redacted session hash and exception class");
        assertFalse(reinforce.contains("catch (Throwable ignore)"),
                "reinforce() must not silently swallow Throwable");
    }

    @Test
    void naverSearchSemaphoreIsInstanceScopedAndSchedulerHasDestroyHook() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("private static final Semaphore REQUEST_SEMAPHORE"),
                "Naver API semaphore must not be static global state");
        assertTrue(source.contains("private final Semaphore REQUEST_SEMAPHORE = new Semaphore(MAX_CONCURRENT_API, true)"),
                "Naver API semaphore should be fair and instance-scoped");
        assertTrue(source.contains("@PreDestroy") && source.contains("shutdownScheduler()"),
                "NaverSearchService should clean up its scheduler on Spring shutdown");
        assertTrue(source.contains("naverIoScheduler = null")
                && source.contains("s.dispose()")
                && source.contains("REQUEST_SEMAPHORE.drainPermits()"),
                "shutdownScheduler() should clear the scheduler reference, dispose it, and drain permits");
    }

    @Test
    void chatWorkflowIntentFallbackLeavesRedactedTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private String inferIntent(");
        int end = source.indexOf("private String detectRisk(", start);
        assertTrue(start >= 0 && end > start, "inferIntent() method span should be locatable");
        String inferIntent = source.substring(start, end);

        assertTrue(inferIntent.contains("TraceStore.putIfAbsent(\"queryTransformer.bypassed\", \"true\")"),
                "inferIntent() fallback should leave a queryTransformer bypass breadcrumb");
        assertTrue(inferIntent.contains("TraceStore.putIfAbsent(\"queryTransformer.reason\", \"infer_intent_failed\")"),
                "inferIntent() fallback should record a stable normalized reason");
        assertTrue(inferIntent.contains("[inferIntent] query transformer bypassed err={}")
                && inferIntent.contains("e.getClass().getSimpleName()"),
                "inferIntent() fallback should log only the exception class");
        assertFalse(inferIntent.contains("SafeRedactor.safeMessage(String.valueOf(q)"),
                "inferIntent() fallback must not log the raw query");
    }

    @Test
    void forceLightSearchModeMarksCheapAuxBeforeDisambiguation() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int cheapFlag = source.indexOf("gctx.setCheapSearchMode(");
        int disambiguationCall = source.indexOf("disambiguationService.clarify(userQuery, recentHistory)");
        assertTrue(cheapFlag >= 0, "ChatWorkflow should mark FORCE_LIGHT as cheap search mode");
        assertTrue(disambiguationCall > cheapFlag,
                "cheap search mode must be set before the disambiguation LLM branch");
        assertTrue(source.contains("com.example.lms.gptsearch.dto.SearchMode.FORCE_LIGHT"),
                "cheap search mode should be tied to the explicit FORCE_LIGHT UI enum");
    }

    @Test
    void forceLightSearchModeCollapsesPlannerFanoutBeforeRoutingPlanService() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int flag = source.indexOf("final boolean forceLightSearchMode =");
        int plannerCall = source.indexOf("routingPlanService.plan(finalQuery", flag);
        int lightBranch = source.indexOf("else if (forceLightSearchMode)", flag);
        assertTrue(flag >= 0, "ChatWorkflow should compute FORCE_LIGHT once for downstream cost gates");
        assertTrue(plannerCall > flag, "routingPlanService fanout call should be locatable after the FORCE_LIGHT flag");
        assertTrue(lightBranch > flag && lightBranch < plannerCall,
                "FORCE_LIGHT must collapse planner fanout before routingPlanService.plan(...) can expand queries");
        assertTrue(source.contains("TraceStore.put(\"search.mode.lightPlannerFanout.skipped\", true)"),
                "planner fanout skip should leave a stable TraceStore breadcrumb");
    }

    @Test
    void forceLightSearchModeFallsBackToPrefetchedWebEvidenceWhenFusedIsEmpty() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int retrieveAll = source.indexOf("fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints)");
        int fallback = source.indexOf("prefetchedWebContents(metaHints", retrieveAll);
        assertTrue(retrieveAll >= 0, "ChatWorkflow retrieveAll span should be locatable");
        assertTrue(fallback > retrieveAll,
                "FORCE_LIGHT should promote prefetched web snippets when downstream fused results are empty");
        assertTrue(source.contains("TraceStore.put(\"search.mode.lightPrefetch.fusedFallback\", true)"),
                "prefetched evidence fallback should leave a stable TraceStore breadcrumb");
        assertTrue(source.contains("metaHints.get(\"prefetch.web.snippets\")"),
                "prefetched evidence fallback should use the existing prefetch.web.snippets metadata");
    }

    @Test
    void forceLightSearchModeBypassesHybridRetrievalWhenPrefetchedWebEvidenceExists() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int lightBranch = source.indexOf("} else if (forceLightSearchMode) {");
        int prefetch = source.indexOf("prefetchedWebContents(metaHints", lightBranch);
        int bypassTrace = source.indexOf("TraceStore.put(\"search.mode.lightPrefetch.hybridBypass\", true)", prefetch);
        int retrieveAll = source.indexOf("fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints)",
                prefetch);
        assertTrue(lightBranch >= 0, "ChatWorkflow should have a FORCE_LIGHT retrieval branch");
        assertTrue(prefetch > lightBranch, "FORCE_LIGHT should inspect existing prefetched web evidence first");
        assertTrue(bypassTrace > prefetch && bypassTrace < retrieveAll,
                "FORCE_LIGHT should leave a stable hybrid bypass breadcrumb before falling back to retrieveAll");
    }

    @Test
    void forceLightSearchModePromotesControllerPrefetchEvenWhenPlanHintsDemoteWebFanout() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int prefetchBlock = source.indexOf("externalCtxProvider.apply(q0)");
        assertTrue(prefetchBlock >= 0, "ChatWorkflow should still have the controller prefetch promotion block");
        int conditionStart = source.lastIndexOf("if (", prefetchBlock);
        assertTrue(conditionStart >= 0, "prefetch promotion condition should be locatable");
        String condition = source.substring(conditionStart, prefetchBlock);
        assertTrue(condition.contains("forceLightSearchMode"),
                "FORCE_LIGHT should promote already-prefetched controller evidence even when later plan hints demote web fanout");
        assertTrue(condition.contains("externalCtxProvider != null"),
                "prefetch promotion must remain provider-guarded");
    }

    @Test
    void prefetchedWebEvidenceKeepsCitableUrlMetadataForLightModeBypass() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int helper = source.indexOf("private static List<dev.langchain4j.rag.content.Content> prefetchedWebContents(");
        int urlHelper = source.indexOf("private static String prefetchedWebSourceUrl(String snippet)", helper);
        assertTrue(helper >= 0 && urlHelper > helper, "prefetched web helper span should be locatable");
        String helperBody = source.substring(helper, urlHelper);
        assertTrue(helperBody.contains("metadata.put(\"url\", sourceUrl)")
                        && helperBody.contains("metadata.put(\"source\", sourceUrl)"),
                "prefetched web evidence should preserve URL metadata for citation promotion");
        assertTrue(helperBody.contains("TextSegment.from(")
                        && helperBody.contains("Metadata.from(metadata)"),
                "prefetched web evidence should be promoted as metadata-bearing Content");
        assertTrue(helperBody.contains("metadata.put(\"prefetch.web.source\", \"controller_prefetch\")"),
                "prefetched web evidence should leave a stable provenance label");
    }

    @Test
    void forceLightSearchModeSkipsVectorRetrievalWhenWebEvidenceIsAlreadyReady() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);

        int topDocs = source.indexOf("List<dev.langchain4j.rag.content.Content> topDocs;");
        int bypassFlag = source.indexOf("boolean forceLightVectorBypass = forceLightSearchMode", topDocs);
        int skipTrace = source.indexOf("TraceStore.put(\"search.mode.lightVector.skipped\", true)", bypassFlag);
        int vectorCall = source.indexOf("vectorDocs = ragSvc.asContentRetriever(pineconeIndexName)", bypassFlag);
        assertTrue(topDocs >= 0 && bypassFlag > topDocs,
                "FORCE_LIGHT vector bypass should be decided after web topDocs are known");
        assertTrue(source.contains("&& topDocs != null")
                && source.contains("&& !topDocs.isEmpty()"),
                "FORCE_LIGHT vector bypass should require real web evidence");
        assertTrue(skipTrace > bypassFlag && skipTrace < vectorCall,
                "FORCE_LIGHT vector skip should be traced before the expensive vector retriever call");
    }

    @Test
    void chatApiControllerMarksForceLightCheapBeforePrefetchSearch() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatApiController.java"),
                StandardCharsets.UTF_8);

        int streamSearchMode = source.indexOf("final boolean allowWeb = shouldUseWebForSearchMode");
        int streamMark = source.indexOf(
                "markCheapSearchMode(gctx, effectiveSearchMode, \"stream.preSearch\")", streamSearchMode);
        int streamSearch = source.indexOf(
                "webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam)", streamSearchMode);
        assertTrue(streamSearchMode >= 0 && streamSearch > streamSearchMode,
                "streaming prefetch search span should be locatable");
        assertTrue(streamMark > streamSearchMode && streamMark < streamSearch,
                "streaming FORCE_LIGHT cheap mode must be marked before prefetch search");
        assertTrue(source.contains("case FORCE_LIGHT, FORCE_DEEP -> true;"),
                "explicit search modes should bypass the AUTO search heuristic");

        int syncSearchMode = source.indexOf("boolean performSearch = shouldUseWebForSearchMode", streamSearch + 1);
        int syncMark = source.indexOf(
                "markCheapSearchMode(__preSearchCtx, effectiveSearchMode, \"sync.preSearch\")", syncSearchMode);
        int syncSearch = source.indexOf(
                "webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam)", streamSearch + 1);
        assertTrue(syncSearchMode >= 0 && syncSearch > syncSearchMode,
                "sync prefetch search span should be locatable");
        assertTrue(syncMark > syncSearchMode && syncMark < syncSearch,
                "sync FORCE_LIGHT cheap mode must be marked before prefetch search");
    }

    @Test
    void cacheOnlyProviderRescueErrorsUseStableOperationalLabels() throws Exception {
        String brave = Files.readString(
                Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"),
                StandardCharsets.UTF_8);
        String naver = Files.readString(
                Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);

        assertTrue(brave.contains("TraceStore.putIfAbsent(\"web.brave.cacheOnly.error\", \"cache_only_failed\")"),
                "Brave cache-only rescue errors should use a stable operational reason");
        assertFalse(brave.contains("TraceStore.putIfAbsent(\"web.brave.cacheOnly.error\", t.getClass().getSimpleName())"),
                "Brave cache-only rescue errors must not expose Java exception class names in TraceStore");
        assertTrue(naver.contains("TraceStore.putIfAbsent(\"web.naver.cacheOnly.error\", \"cache_only_failed\")"),
                "Naver cache-only rescue errors should use a stable operational reason");
        assertFalse(naver.contains("TraceStore.putIfAbsent(\"web.naver.cacheOnly.error\", t.getClass().getSimpleName())"),
                "Naver cache-only rescue errors must not expose Java exception class names in TraceStore");
    }

    @Test
    void pendingMemorySoakFlushFailureLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/PendingMemorySoakScheduler.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("outcome = vectorStoreService.flush();");
        int end = source.indexOf("if (outcome != null && outcome.durable())", start);
        assertTrue(start >= 0 && end > start, "explicit flush catch span should be locatable");
        String flushCatch = source.substring(start, end);

        assertFalse(source.contains("vectorStoreService.triggerFlushIfDue();"),
                "pending soak must use explicit flush so promotion follows a durable outcome");
        assertTrue(flushCatch.contains("TraceStore.inc(\"vectorstore.flush.failed\")"),
                "pending soak flush failure should leave a vectorstore flush breadcrumb");
        assertTrue(flushCatch.contains("[PENDING_SOAK] flush failed errorHash={} errorLength={}")
                        && flushCatch.contains("SafeRedactor.hashValue(messageOf(flushErr))")
                        && flushCatch.contains("messageLength(flushErr)"),
                "pending soak flush failure should log only a hash and length");
        assertFalse(flushCatch.contains("catch (Exception ignore)"),
                "pending soak flush failure must not be silently swallowed");
    }

    @Test
    void indexingSchedulerDoesNotAdvanceLastFetchTimeWhenVectorFlushFails() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/scheduler/IndexingScheduler.java"),
                StandardCharsets.UTF_8);

        int flush = source.indexOf("outcome = vectorStoreService.flush();");
        int predicate = source.indexOf(
                "boolean batchPersisted = outcome != null && outcome.durable();", flush);
        int durableBranch = source.indexOf("if (batchPersisted)", predicate);
        int reinforcement = source.indexOf("memorySvc.reinforceWithSnippet", durableBranch);
        int advance = source.indexOf("lastFetchTime.set(LocalDateTime.now())", durableBranch);
        int trace = source.indexOf("TraceStore.inc(\"indexing.flush.failed\")", flush);
        int reason = source.indexOf("\"indexing.flush.failed.reason\"", flush);

        assertTrue(flush >= 0, "scheduleIndexing() must consume VectorFlushOutcome");
        assertFalse(source.contains("boolean flushOk = false;"),
                "normal return from flush must not imply durable persistence");
        assertFalse(source.contains("flushOk = true;"),
                "normal return from flush must not imply durable persistence");
        assertTrue(predicate > flush,
                "durability must be null-safe and derived from outcome.durable()");
        assertTrue(durableBranch > predicate,
                "reinforcement and cursor movement must have a durable branch");
        assertTrue(reinforcement > durableBranch && advance > reinforcement,
                "reinforcement and lastFetchTime movement must be inside the durable branch");
        assertTrue(trace > flush && reason > flush,
                "non-durable or exceptional flushes need a failure breadcrumb and fixed reason");
        assertTrue(source.contains("\"exception\"")
                        && source.contains("\"null_outcome\"")
                        && source.contains("\"backoff\"")
                        && source.contains("\"partial\"")
                        && source.contains("\"store_failure\"")
                        && source.contains("\"non_durable\""),
                "flush failure breadcrumbs must use fixed categorical reasons");
    }

    @Test
    void nightmareBreakerEvictsStaleDynamicStateAndPolicyEntries() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/infra/resilience/NightmareBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("@Scheduled(fixedDelayString = \"${nightmare.breaker.evict-interval-ms:300000}\")"),
                "NightmareBreaker should have a small scheduled stale-state eviction hook");
        assertTrue(source.contains("void evictStaleStates()"),
                "NightmareBreaker stale-state eviction should be package-visible for focused tests");
        assertTrue(source.contains("lastActivityTick") && source.contains("ticker.getAsLong()"),
                "NightmareBreaker.GateState should track monotonic activity for dynamic keys");
        assertTrue(source.contains("state.mode != BreakerMode.CLOSED || state.inFlight != 0"),
                "NightmareBreaker should retire only idle CLOSED gates");
        assertTrue(source.contains("gate.state.compareAndSet(state, retired)"),
                "NightmareBreaker should retire stale gates with CAS");
        assertTrue(source.contains("states.remove(key, gate)"),
                "NightmareBreaker should remove only the exact retired Gate identity");
        assertTrue(source.contains("policyCache.keySet().removeIf(k -> !states.containsKey(k))"),
                "NightmareBreaker should trim policyCache entries after stale states are removed");
    }

    @Test
    void wiringPrecheckCoversHighRiskOptionalRagAndProviderBeans() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/boot/WiringPrecheckRunner.java"),
                StandardCharsets.UTF_8);

        String[] optionalLabels = {
                "DebugEventStore",
                "TraceSnapshotStore",
                "QueryTransformer",
                "NaverSearchService",
                "BraveSearchService",
                "NightmareBreaker",
                "DynamicRetrievalHandlerChain",
                "KnowledgeGraphHandler",
                "BrainStateService",
                "RagGraphExecutor",
                "EvidenceRepairHandler"
        };
        for (String label : optionalLabels) {
            assertTrue(source.contains("checkSingle(\"" + label + "\""),
                    "WiringPrecheckRunner should report optional bean presence for " + label);
        }
        assertTrue(source.contains("SafeRedactor.hashValue(name)"),
                "WiringPrecheckRunner should keep bean names redacted in startup diagnostics");
        assertTrue(source.contains("traceSuppressed(\"wiring.braveKeySnapshot\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"wiring.hybridAwaitSnapshot\", ignore);"));
    }

    @Test
    void queryRouteHandlerFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/QueryRouteHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceRouteFailure(\"metadata_read\", text, metadataReadEx)"),
                "metadata read failures should leave a route trace breadcrumb");
        assertTrue(source.contains("traceRouteFailure(\"metadata_write\", text, metadataWriteEx)"),
                "metadata write failures should leave a route trace breadcrumb");
        assertTrue(source.contains("traceRouteFailure(\"decision\", text, decisionEx)"),
                "decision engine failures should leave a route trace breadcrumb");
        assertTrue(source.contains("TraceStore.inc(\"retrieval.route.\" + safeStage + \".failed\")"),
                "route failures should increment a stage-specific trace counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"retrieval.route.queryHash\"")
                        && source.contains("SafeRedactor.hash12(text)")
                        && source.contains("TraceStore.putIfAbsent(\"retrieval.route.queryLength\""),
                "route failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "QueryRouteHandler must not silently swallow route failures");
    }

    @Test
    void searchCostGuardFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/handler/SearchCostGuardHandler.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceCostGuardFailure(\"estimate\", text, estimateEx)"),
                "token estimation failures should leave a cost guard trace breadcrumb");
        assertTrue(source.contains("traceCostGuardFailure(\"relief\", text, reliefEx)"),
                "relief callback failures should leave a cost guard trace breadcrumb");
        assertTrue(source.contains("TraceStore.inc(\"retrieval.costGuard.\" + safeStage + \".failed\")"),
                "cost guard failures should increment a stage-specific trace counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"retrieval.costGuard.queryHash\"")
                        && source.contains("SafeRedactor.hash12(text)")
                        && source.contains("TraceStore.putIfAbsent(\"retrieval.costGuard.queryLength\""),
                "cost guard failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "SearchCostGuardHandler must not silently swallow guard failures");
    }

    @Test
    void gradleHostSplitSupportsExternalBuildRootForAclBlockedWorkspaceBuilds() throws Exception {
        String source = Files.readString(Path.of("build.gradle.kts"), StandardCharsets.UTF_8);

        assertTrue(source.contains("AWX_BUILD_ROOT_DIR")
                        && source.contains("awx.buildRootDir"),
                "Gradle host-split should allow an explicit host-local build root override");
        assertTrue(source.contains("awxBuildRootDir")
                        && source.contains("awxProjectBuildDir(project)")
                        && source.contains("layout.buildDirectory.set(awxProjectBuildDir(project))"),
                "Gradle host-split should route each project build dir through the override-aware helper");
        assertTrue(source.contains("project.path.trim(':').replace(':', '-')"),
                "External build root should keep subproject output directories separated");
    }

    @Test
    void compositeQueryPreprocessorFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/pre/CompositeQueryContextPreprocessor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;"),
                "Composite preprocessor should emit TraceStore breadcrumbs for fail-soft delegates");
        assertTrue(source.contains("tracePreprocessorFailure(\"enrich\"")
                        && source.contains("tracePreprocessorFailure(\"detect_domain\"")
                        && source.contains("tracePreprocessorFailure(\"infer_intent\"")
                        && source.contains("tracePreprocessorFailure(\"interaction_rules\""),
                "all preprocessor fail-soft paths should record the failed stage");
        assertTrue(source.contains("TraceStore.inc(\"query.preprocessor.\" + safeStage + \".failed\")"),
                "preprocessor failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"query.preprocessor.queryHash\"")
                        && source.contains("SafeRedactor.hash12(query)")
                        && source.contains("TraceStore.putIfAbsent(\"query.preprocessor.queryLength\""),
                "preprocessor failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "CompositeQueryContextPreprocessor must not silently swallow delegate failures");
    }

    @Test
    void guardrailQueryPreprocessorInternalFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/pre/GuardrailQueryPreprocessor.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;")
                        && source.contains("import com.example.lms.trace.SafeRedactor;"),
                "Guardrail preprocessor should emit redacted TraceStore breadcrumbs");
        assertTrue(source.contains("traceGuardrailFailure(\"cognitive_state\", original, stateEx)")
                        && source.contains("traceGuardrailFailure(\"typo_rewrite\", s, typoEx)")
                        && source.contains("traceGuardrailFailure(\"extract_cognitive_state\", q, stateEx)"),
                "guardrail internal fail-soft paths should record the failed stage");
        assertTrue(source.contains("TraceStore.inc(\"query.guardrail.\" + safeStage + \".failed\")"),
                "guardrail failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"query.guardrail.queryHash\"")
                        && source.contains("SafeRedactor.hash12(query)")
                        && source.contains("TraceStore.putIfAbsent(\"query.guardrail.queryLength\""),
                "guardrail failure trace should use query hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "GuardrailQueryPreprocessor must not silently swallow internal failures");
    }

    @Test
    void webClientDiagnosticsFailuresLeaveRedactedTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/WebClientDiagnostics.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("import com.example.lms.search.TraceStore;"),
                "WebClient diagnostics should emit TraceStore breadcrumbs for its own fail-soft paths");
        assertTrue(source.contains("traceWebClientDiagnosticsFailure(\"request\", uri, requestEx)")
                        && source.contains("traceWebClientDiagnosticsFailure(\"response\", uri, responseEx)"),
                "request and response diagnostics failures should record their failed stage");
        assertTrue(source.contains("TraceStore.inc(\"webclient.diagnostics.\" + safeStage + \".failed\")"),
                "WebClient diagnostics failures should increment a stage-specific counter");
        assertTrue(source.contains("TraceStore.putIfAbsent(\"webclient.diagnostics.targetHash\"")
                        && source.contains("SafeRedactor.hash12(target)")
                        && source.contains("TraceStore.putIfAbsent(\"webclient.diagnostics.targetLength\""),
                "WebClient diagnostics failure trace should use target hash and length only");
        assertFalse(source.contains("catch (Exception ignore)"),
                "WebClientDiagnostics must not silently swallow diagnostics failures");
    }

    @Test
    void ragSubsystemNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Integer.parseInt(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return (int) Long.parseLong(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Long.parseLong(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/guard/GuardContext.java"),
                "return Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"),
                "return Long.parseLong(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java"),
                "return Double.parseDouble(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"),
                "double parsed = Double.parseDouble(String.valueOf(value).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/NeedleKeptRatioRewardAspect.java"),
                "double parsed = Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"),
                "return Double.parseDouble(s);");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagGraphControlPolicy.java"),
                "return Integer.parseInt(String.valueOf(raw).trim());");
        assertParserCatchNarrowed(
                Path.of("main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java"),
                "double value = Double.parseDouble(String.valueOf(raw).trim());");
    }

    private static void assertParserCatchNarrowed(Path path, String parserCall) throws Exception {
        String source = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable in " + path + ": " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                () -> "numeric parser fallback must not hide non-parse failures in " + path);
        assertTrue(window.contains("catch (NumberFormatException"),
                () -> "numeric parser fallback should catch only NumberFormatException in " + path);
    }
}
