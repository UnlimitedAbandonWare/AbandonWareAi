package com.example.lms.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTraceConsoleLoggerTest {

    @AfterEach
    void clearRequestState() {
        MDC.clear();
        TraceStore.clear();
    }

    @Test
    void defaultOffDoesNotEmitWithoutRequestFlag() {
        Logger logger = (Logger) LoggerFactory.getLogger("SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            new SearchTraceConsoleLogger(false).maybeLog(
                    "sync",
                    trace("private default-off query", "private step query"),
                    List.of("private snippet"),
                    null,
                    null,
                    Map.of("web.effectiveQuery", "private default-off query"));

            assertTrue(appender.list.isEmpty());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void requestEnabledLogsOnlyHashAndCountsForQueryBearingValues() {
        Logger logger = (Logger) LoggerFactory.getLogger("SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
        try {
            MDC.put("dbgSearch", "1");
            MDC.put("sid", "raw-search-session-id");
            MDC.put("trace", "raw-search-trace-id");
            String rawQuery = "private search query with sk-local-secret-value";
            String rawStepQuery = "step query ownerToken=owner-token-secret";
            String rawRequestedModel = "gpt-private-owner-token";
            String rawSubstituteModel = "gpt-substitute-api_key=test-fixture";
            TraceStore.put("web.naver.requestedCount", 5);
            TraceStore.put("web.naver.returnedCount", 3);
            TraceStore.put("web.naver.afterFilterCount", 0);
            TraceStore.put("web.naver.afterFilterStarved", true);
            TraceStore.put("web.naver.failureReason", "after-filter-starvation");
            TraceStore.put("web.naver.queryHash", SafeRedactor.hashValue(rawQuery));
            TraceStore.put("web.naver.queryLength", rawQuery.length());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("embed.matryoshka.slice.actual", 2560);
            meta.put("embed.matryoshka.slice.target", 1536);
            meta.put("embed.matryoshka.slice.reductionRatio", 0.40d);
            meta.put("embed.matryoshka.slice.expectedDistanceOpsRatio", 0.6d);
            meta.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup", 1.6667d);
            meta.put("llm.modelGuard.mode", "FAIL_FAST");
            meta.put("llm.modelGuard.requestedModel", rawRequestedModel);
            meta.put("llm.modelGuard.substituteChatModel", rawSubstituteModel);
            meta.put("llm.modelGuard.requestedModelHash", "hash:requested");
            meta.put("llm.modelGuard.requestedModelLength", rawRequestedModel.length());
            meta.put("llm.modelGuard.substituteChatModelHash", "hash:substitute");
            meta.put("llm.modelGuard.substituteChatModelLength", rawSubstituteModel.length());
            meta.put("llm.modelGuard.triggered", true);
            meta.put("llm.modelGuard.endpoint", "/v1/chat/completions");
            meta.put("llm.modelGuard.failReason", "EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
            meta.put("boosterMode.active", "EXTREMEZ");
            meta.put("boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA"));
            meta.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
            meta.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
            meta.put("routing.executionPlan.primaryMode", "EXTREMEZ");
            meta.put("routing.executionPlan.triggers", List.of("lowRecall", "highRiskTail"));
            meta.put("routing.executionPlan.extremeZ", true);
            meta.put("routing.executionPlan.overdrive", false);
            meta.put("routing.executionPlan.hypernova", false);
            meta.put("routing.executionPlan.applied", true);
            meta.put("routing.executionPlan.applied.primaryMode", "EXTREMEZ");
            meta.put("routing.executionPlan.applied.triggers", List.of("lowRecall", "highRiskTail"));
            meta.put("routing.executionPlan.applied.stages", List.of("plan", "guard", "apply"));
            meta.put("specialMode.conflict.suppressed", "OVERDRIVE,HYPERNOVA");
            meta.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
            meta.put("hypernova.twpmP", 4.0d);
            meta.put("hypernova.cvarPhi", 0.618d);
            meta.put("hypernova.clampApplied", true);
            meta.put("hypernova.dppApplied", true);
            meta.put("hypernova.sourceScoreScaleMismatchCount", 2);
            meta.put("hypernova.sourceScoreScaleMismatchPolicy", "fallback_to_base_norm");
            meta.put("nova.hypernova.riskK.alloc.sum", 12);
            meta.put("hypernova.riskKAlloc", Map.of("used", true, "totalK", 12, "sum", 12));
            meta.put("retrievalOrder.lastSetBy", "PLAN_DSL");
            meta.put("retrievalOrder.lastOrder", List.of("VECTOR", "KG", "WEB"));
            meta.put("retrievalOrder.lastOrderSize", 3);
            meta.put("retrievalOrder.authority.owner", "PLAN_DSL");
            meta.put("retrievalOrder.authority.suppressedOwner", "MoE");
            meta.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
            meta.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
            meta.put("retrieval.order.strategy.applied", false);
            meta.put("web.effectiveQuery", rawQuery);
            meta.put("queryPlanner.finalUsed", rawQuery);
            meta.put("queryPlanner.llmProposed", List.of(rawQuery));

            new SearchTraceConsoleLogger(false).maybeLog(
                    "sync",
                    trace(rawQuery, rawStepQuery),
                    List.of("raw snippet " + rawQuery),
                    null,
                    null,
                    meta);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("sidHash=hash:"));
            assertTrue(logged.contains("traceHash=hash:"));
            assertTrue(logged.contains("queryHash=hash:"));
            assertTrue(logged.contains("queryLength=" + rawQuery.length()));
            assertTrue(logged.contains("provider naver"));
            assertTrue(logged.contains("requested=5"));
            assertTrue(logged.contains("returned=3"));
            assertTrue(logged.contains("afterFilter=0"));
            assertTrue(logged.contains("meta embed.matryoshka.slice.actual=2560"), logged);
            assertTrue(logged.contains("meta embed.matryoshka.slice.target=1536"), logged);
            assertTrue(logged.contains("meta embed.matryoshka.slice.reductionRatio=0.4"), logged);
            assertTrue(logged.contains("meta embed.matryoshka.slice.expectedDistanceOpsRatio=0.6"), logged);
            assertTrue(logged.contains("meta embed.matryoshka.slice.expectedDistanceOpsSpeedup=1.6667"), logged);
            assertTrue(logged.contains("meta llm.modelGuard.requestedModelHash=hash:requested"), logged);
            assertTrue(logged.contains("meta llm.modelGuard.requestedModelLength=" + rawRequestedModel.length()), logged);
            assertTrue(logged.contains("meta llm.modelGuard.substituteChatModelHash=hash:substitute"), logged);
            assertTrue(logged.contains("meta llm.modelGuard.substituteChatModelLength=" + rawSubstituteModel.length()), logged);
            assertTrue(logged.contains("meta llm.modelGuard.triggered=true"), logged);
            assertTrue(logged.contains("meta llm.modelGuard.endpoint=/v1/chat/completions"), logged);
            assertTrue(logged.contains("meta llm.modelGuard.failReason=EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH"), logged);
            assertTrue(logged.contains("meta boosterMode.active=EXTREMEZ"), logged);
            assertTrue(logged.contains("meta boosterMode.excludedModes=OVERDRIVE,HYPERNOVA"), logged);
            assertTrue(logged.contains("meta boosterMode.priority=EXTREMEZ_HYPERNOVA_OVERDRIVE"), logged);
            assertTrue(logged.contains("meta boosterMode.exclusionReason=single_primary_mode:EXTREMEZ_HYPERNOVA_OVERDRIVE"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.primaryMode=EXTREMEZ"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.triggers=lowRecall,highRiskTail"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.applied=true"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.applied.primaryMode=EXTREMEZ"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.applied.triggers=lowRecall,highRiskTail"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.applied.stages=plan,guard,apply"), logged);
            assertTrue(logged.contains("meta specialMode.conflict.suppressed=OVERDRIVE_HYPERNOVA"), logged);
            assertTrue(logged.contains("meta hypernova.twpmP=4.0"), logged);
            assertTrue(logged.contains("meta hypernova.cvarPhi=0.618"), logged);
            assertTrue(logged.contains("meta hypernova.clampApplied=true"), logged);
            assertTrue(logged.contains("meta hypernova.dppApplied=true"), logged);
            assertTrue(logged.contains("meta hypernova.sourceScoreScaleMismatchCount=2"), logged);
            assertTrue(logged.contains("meta hypernova.sourceScoreScaleMismatchPolicy=fallback_to_base_norm"), logged);
            assertTrue(logged.contains("meta nova.hypernova.riskK.alloc.sum=12"), logged);
            assertTrue(logged.contains("meta hypernova.riskKAlloc="), logged);
            assertTrue(logged.contains("used=true"), logged);
            assertTrue(logged.contains("totalK=12"), logged);
            assertTrue(logged.contains("sum=12"), logged);
            assertTrue(logged.contains("meta retrievalOrder.lastSetBy=PLAN_DSL"), logged);
            assertTrue(logged.contains("meta retrievalOrder.lastOrder=VECTOR,KG,WEB"), logged);
            assertTrue(logged.contains("meta retrievalOrder.authority.owner=PLAN_DSL"), logged);
            assertTrue(logged.contains("meta retrievalOrder.authority.suppressedOwner=MoE"), logged);
            assertTrue(logged.contains("meta retrievalOrder.authority.reason=rulebreak_speed_first"), logged);
            assertTrue(logged.contains("meta retrievalOrder.authority.suppressedReason=plan_dsl_preempts_strategy_selection"), logged);
            assertTrue(logged.contains("meta retrieval.order.strategy.applied=false"), logged);
            assertFalse(logged.contains(rawQuery), logged);
            assertFalse(logged.contains(rawStepQuery), logged);
            assertFalse(logged.contains(rawRequestedModel), logged);
            assertFalse(logged.contains(rawSubstituteModel), logged);
            assertFalse(logged.contains("raw-search-session-id"), logged);
            assertFalse(logged.contains("raw-search-trace-id"), logged);
            assertFalse(logged.contains("sk-local-secret-value"), logged);
            assertFalse(logged.contains("owner-token-secret"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void boostModeLogsRoutingConflictSummaryWithoutRequestVerboseFlag() {
        Logger logger = (Logger) LoggerFactory.getLogger("SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            SearchDebugBoost boost = new SearchDebugBoost(true, 1, "websearch", "TIMEOUT", "", "");
            boost.boost("nightmare timeout");
            MDC.clear();

            SearchTraceConsoleLogger consoleLogger = new SearchTraceConsoleLogger(false);
            ReflectionTestUtils.setField(consoleLogger, "debugBoost", boost);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("boosterMode.active", "EXTREMEZ");
            meta.put("boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA"));
            meta.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
            meta.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
            meta.put("routing.executionPlan.primaryMode", "EXTREMEZ");
            meta.put("routing.executionPlan.triggers", List.of("lowRecall", "timeout"));
            meta.put("routing.executionPlan.extremeZ", true);
            meta.put("routing.executionPlan.overdrive", false);
            meta.put("routing.executionPlan.hypernova", false);

            consoleLogger.maybeLog(
                    "boost",
                    trace("private boost query", "private boost step"),
                    List.of(),
                    null,
                    null,
                    meta);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("dbgBoost active=1"), logged);
            assertTrue(logged.contains("meta boosterMode.active=EXTREMEZ"), logged);
            assertTrue(logged.contains("meta boosterMode.excludedModes=OVERDRIVE,HYPERNOVA"), logged);
            assertTrue(logged.contains("meta boosterMode.priority=EXTREMEZ_HYPERNOVA_OVERDRIVE"), logged);
            assertTrue(logged.contains("meta boosterMode.exclusionReason=single_primary_mode:EXTREMEZ_HYPERNOVA_OVERDRIVE"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.primaryMode=EXTREMEZ"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.triggers=lowRecall,timeout"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.extremeZ=true"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.overdrive=false"), logged);
            assertTrue(logged.contains("meta routing.executionPlan.hypernova=false"), logged);
            assertFalse(logged.contains("private boost query"), logged);
            assertFalse(logged.contains("private boost step"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
            MDC.clear();
        }
    }

    @Test
    void requestEnabledLogsProviderSkipReasonAndRetryAfterWithoutRawReason() {
        Logger logger = (Logger) LoggerFactory.getLogger("SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            MDC.put("dbgSearch", "1");
            String rawReason = "disabled ownerToken=secret api_key=test-fixture";
            TraceStore.put("web.serpapi.skipped", true);
            TraceStore.put("web.serpapi.skipped.reason", rawReason);
            TraceStore.put("web.serpapi.providerDisabled", true);
            TraceStore.put("web.serpapi.disabledReason", "missing_serpapi_api_key");
            TraceStore.put("web.serpapi.failureReason", "provider-disabled");
            TraceStore.put("web.serpapi.rateLimited", true);
            TraceStore.put("web.serpapi.retryAfterMs", 13000L);

            new SearchTraceConsoleLogger(false).maybeLog(
                    "sync",
                    trace("private taxonomy console query", "private step query"),
                    List.of(),
                    null,
                    null,
                    Map.of());

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("provider serpapi"), logged);
            assertTrue(logged.contains("skipped=true"), logged);
            assertTrue(logged.contains("skipReason=hash:"), logged);
            assertTrue(logged.contains("reason=provider-disabled"), logged);
            assertTrue(logged.contains("retryAfterMs=13000"), logged);
            assertFalse(logged.contains(rawReason), logged);
            assertFalse(logged.contains("ownerToken"), logged);
            assertFalse(logged.contains("api_key=test-fixture"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void verboseMetaLogsStageCountsFromLastFallbackWhenCanonicalKeyIsAbsent() {
        Logger logger = (Logger) LoggerFactory.getLogger("SEARCH_TRACE");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        try {
            MDC.put("dbgSearch", "1");
            Map<String, Object> stageCounts = new LinkedHashMap<>();
            stageCounts.put("NOFILTER_SAFE", 2);
            stageCounts.put("OFFICIAL", 0);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("stageCountsSelectedFromOut.last", stageCounts);

            new SearchTraceConsoleLogger(false).maybeLog(
                    "sync",
                    trace("private stage-count query", "private stage-count step"),
                    List.of(),
                    null,
                    null,
                    meta);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("meta stageCountsSelectedFromOut={NOFILTER_SAFE=2, OFFICIAL=0}"), logged);
            assertFalse(logged.contains("stageCountsSelectedFromOut.last"), logged);
            assertFalse(logged.contains("private stage-count query"), logged);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void debugEventSummaryUsesHashOnlyProviderMetrics() {
        MDC.put("dbgSearch", "1");
        String rawQuery = "debug event private query";
        TraceStore.put("web.brave.requestedCount", 4);
        TraceStore.put("web.brave.returnedCount", 0);
        TraceStore.put("web.brave.afterFilterCount", 0);
        TraceStore.put("web.brave.providerEmpty", true);
        TraceStore.put("web.brave.failureReason", "provider-empty");
        TraceStore.put("web.brave.queryHash", SafeRedactor.hashValue(rawQuery));
        TraceStore.put("web.brave.queryLength", rawQuery.length());
        TraceStore.put("web.tavily.requestedCount", 2);
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(rawQuery + " tavily"));
        TraceStore.put("web.tavily.queryLength", rawQuery.length() + 7);

        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);

        SearchTraceConsoleLogger consoleLogger = new SearchTraceConsoleLogger(false);
        ReflectionTestUtils.setField(consoleLogger, "debugEventStore", store);

        consoleLogger.maybeLog(
                "stream",
                trace(rawQuery, "debug step raw query"),
                List.of("debug raw snippet " + rawQuery),
                null,
                null,
                Map.of("web.effectiveQuery", rawQuery));

        List<DebugEvent> events = store.list(10);
        assertFalse(events.isEmpty());
        String data = String.valueOf(events.get(0).data());
        assertTrue(data.contains("queryHash"));
        assertTrue(data.contains("providers"));
        assertTrue(data.contains("brave"));
        assertTrue(data.contains("provider-empty"));
        assertTrue(data.contains("tavily"));
        assertTrue(data.contains("provider-disabled"));
        assertFalse(data.contains(rawQuery), data);
        assertFalse(data.contains("debug step raw query"), data);
    }

    @Test
    void consoleKeyListsIncludeSerpApiAndTavilyAwaitTimeoutSummaries() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java"));

        assertTrue(source.contains("\"web.await.events.summary.engine.SerpApi.cause.await_timeout.count\""));
        assertTrue(source.contains("\"web.await.events.summary.engine.Tavily.cause.await_timeout.count\""));
    }

    @Test
    void consoleKeyListIncludesDetailedStarvationFallbackKpis() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java"));

        assertTrue(source.contains("\"starvationFallback.used\""));
        assertTrue(source.contains("\"starvationFallback.poolUsed\""));
        assertTrue(source.contains("\"starvationFallback.pool.safe.size\""));
        assertTrue(source.contains("\"starvationFallback.pool.dev.size\""));
        assertTrue(source.contains("\"starvationFallback.poolSafeEmpty\""));
        assertTrue(source.contains("\"starvationFallback.count\""));
        assertTrue(source.contains("\"starvationFallback.added\""));
    }

    @Test
    void consoleKeyListIncludesVectorFallbackKpis() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/java/com/example/lms/trace/SearchTraceConsoleLogger.java"));

        assertTrue(source.contains("\"vectorFallback.used\""));
        assertTrue(source.contains("\"vectorFallback.reason\""));
        assertTrue(source.contains("\"vectorFallback.effectiveTopK\""));
    }

    private static NaverSearchService.SearchTrace trace(String rawQuery, String rawStepQuery) {
        NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
        trace.query = rawQuery;
        trace.provider = "Naver";
        trace.totalMs = 42L;
        trace.steps.add(new NaverSearchService.SearchStep(rawStepQuery, 3, 0, 12L));
        return trace;
    }
}
