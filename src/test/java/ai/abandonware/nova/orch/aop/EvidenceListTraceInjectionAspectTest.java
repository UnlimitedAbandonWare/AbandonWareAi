package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceListTraceInjectionAspectTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void evidenceListTraceInjectionDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListTraceInjectionAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Evidence list trace injection needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void evidenceTraceLongParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListTraceInjectionAspect.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Long.parseLong(s);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "evidence trace long parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "evidence trace numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "evidence trace numeric parser fallback should catch only NumberFormatException");
    }

    @Test
    void traceInjectionFallbacksLeaveRedactedErrorBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/EvidenceListTraceInjectionAspect.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"diagnostics.context\", t);"));
        assertTrue(source.contains("traceSuppressed(\"parseLong\", ignore);"));
        assertTrue(source.contains("[nova][evidence-list-trace-injection] suppressed stage={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(error)), messageLength(error)"));
    }

    @Test
    void toLongDropsNonFiniteNumbers() throws Exception {
        Method method = EvidenceListTraceInjectionAspect.class.getDeclaredMethod("toLong", Object.class);
        method.setAccessible(true);

        assertEquals(0L, method.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals(0L, method.invoke(null, Double.NEGATIVE_INFINITY));
        assertEquals(0L, method.invoke(null, Double.NaN));
    }

    @Test
    void prependAliasControlsInjectionPlacement() throws Exception {
        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(
                new MockEnvironment().withProperty("nova.orch.evidence-list.prepend", "true"));

        String injected = inject(aspect, "answer", "diag");

        assertTrue(injected.startsWith("<!-- NOVA_TRACE_INJECTED -->"));
    }

    @Test
    void defaultInjectionPlacementAppendsTraceBlock() throws Exception {
        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String injected = inject(aspect, "answer", "diag");

        assertTrue(injected.startsWith("answer"));
    }

    @Test
    void blankBaseInjectionKeepsReadableFallbackBeforeDiagnosticsEvenWhenPrependRequested() throws Exception {
        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(
                new MockEnvironment().withProperty("nova.orch.evidence-list.prepend", "true"));

        String injected = inject(aspect, "   ", "diag");

        assertTrue(injected.startsWith("The evidence answer body was blank. Please retry the request."), injected);
        assertTrue(injected.contains("<!-- NOVA_TRACE_INJECTED -->"), injected);
        assertTrue(injected.indexOf("The evidence answer body was blank.") < injected.indexOf("<!-- NOVA_TRACE_INJECTED -->"),
                injected);
        assertEquals(Boolean.TRUE, TraceStore.get("orch.evidenceList.traceInjected.blankBaseFallback"));
    }

    @Test
    void diagnosticsPreferExecutedQueryHashOverRawQuery() throws Exception {
        String rawQuery = "private evidence trace injected query";
        TraceStore.put("web.failsoft.executedQuery", rawQuery);
        TraceStore.put("web.failsoft.executedQueryHash", SafeRedactor.hashValue(rawQuery));
        TraceStore.put("outCount", 0);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawQuery), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawQuery)), diag);
    }

    @Test
    void diagnosticsSummarizeAutoReportAndTimelineRawText() throws Exception {
        String rawAutoReport = "private auto report should not remain in evidence diagnostics";
        String rawTimelineData = "private timeline data should not remain in evidence diagnostics";
        TraceStore.put("orch.autoReport.text", rawAutoReport);
        TraceStore.put("orch.events.v1", List.of(Map.of(
                "seq", 1,
                "kind", "unit",
                "data", rawTimelineData)));

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawAutoReport), diag);
        assertFalse(diag.contains(rawTimelineData), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawAutoReport)), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawTimelineData)), diag);
    }

    @Test
    void selectedKeysBlockSummarizesRawDiagnosticValues() throws Exception {
        String rawQuery = "private selected key query should not remain";
        String rawGuardNote = "private selected guard note should not remain";
        String rawAutoReport = "private selected auto report should not remain";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("web.failsoft.executedQuery", rawQuery);
        TraceStore.put("guard.final.note", rawGuardNote);
        TraceStore.put("orch.autoReport.text", rawAutoReport);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(
                new MockEnvironment().withProperty(
                        "nova.orch.evidence-list.trace-injection.include-selected-keys", "true"));

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawQuery), diag);
        assertFalse(diag.contains(rawGuardNote), diag);
        assertFalse(diag.contains(rawAutoReport), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawQuery)), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawGuardNote)), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawAutoReport)), diag);
    }

    @Test
    void selectedKeysBlockIncludesSerpApiAndTavilyAwaitCooldownKeys() throws Exception {
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutApplied", true);
        TraceStore.put("web.failsoft.rateLimitBackoff.tavily.awaitTimeoutApplied", true);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(
                new MockEnvironment().withProperty(
                        "nova.orch.evidence-list.trace-injection.include-selected-keys", "true"));

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutApplied"), diag);
        assertTrue(diag.contains("web.failsoft.rateLimitBackoff.tavily.awaitTimeoutApplied"), diag);
    }

    @Test
    void auxDiagnosticsSummarizeRawPayloadValues() throws Exception {
        String rawKeywordSelection = "private keyword selection payload should not remain";
        String rawQueryTransformer = "private query transformer payload should not remain";
        TraceStore.put("aux.keywordSelection", rawKeywordSelection);
        TraceStore.put("aux.queryTransformer", rawQueryTransformer);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawKeywordSelection), diag);
        assertFalse(diag.contains(rawQueryTransformer), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawKeywordSelection)), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawQueryTransformer)), diag);
    }

    @Test
    void selectedKeysBlockTraceLabelsFreeFormReasons() throws Exception {
        String rawReason = "private selected reason text should not remain";
        TraceStore.put("dbg.search.enabled", true);
        TraceStore.put("guard.degrade.reason", rawReason);
        TraceStore.put("web.failsoft.starvationFallback.trigger", "pool_safe_empty");

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(
                new MockEnvironment().withProperty(
                        "nova.orch.evidence-list.trace-injection.include-selected-keys", "true"));

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawReason)), diag);
        assertTrue(diag.contains("pool_safe_empty"), diag);
    }

    @Test
    void mainDiagnosticsTraceLabelsFreeFormReasonsAndCauses() throws Exception {
        String rawOrchReason = "private orch reason should not remain";
        String rawBraveReason = "private brave skipped reason should not remain";
        String rawAwaitCause = "private await root cause should not remain";
        String rawAuxReason = "private aux degraded reason should not remain";
        String rawTrigger = "private starvation trigger should not remain";
        TraceStore.put("orch.reason", rawOrchReason);
        TraceStore.put("web.brave.skipped.reason", rawBraveReason);
        TraceStore.put("web.naver.skipped.reason", "breaker_open");
        TraceStore.put("web.await.root.cause", rawAwaitCause);
        TraceStore.put("aux.queryTransformer.degraded.reason", rawAuxReason);
        TraceStore.put("web.failsoft.starvationFallback.trigger", rawTrigger);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawOrchReason), diag);
        assertFalse(diag.contains(rawBraveReason), diag);
        assertFalse(diag.contains(rawAwaitCause), diag);
        assertFalse(diag.contains(rawAuxReason), diag);
        assertFalse(diag.contains(rawTrigger), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawOrchReason)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawBraveReason)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawAwaitCause)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawAuxReason)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawTrigger)), diag);
        assertTrue(diag.contains("breaker_open"), diag);
    }

    @Test
    void mainDiagnosticsDetectSerpApiAndTavilyBreakerOpenAdvice() throws Exception {
        TraceStore.put("web.serpapi.skipped.reason", "breaker_open");
        TraceStore.put("web.tavily.skipped.reason", "breaker_open");

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("provider breaker_open"), diag);
        assertTrue(diag.contains("serpapi/tavily"), diag);
    }

    @Test
    void starvationFallbackPayloadSummarizesRawValues() throws Exception {
        String rawFallbackPayload = "private fallback payload should not remain";
        TraceStore.put("web.failsoft.starvationFallback", Map.of(
                "query", rawFallbackPayload,
                "used", true));

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawFallbackPayload), diag);
        assertTrue(diag.contains(SafeRedactor.hash12(rawFallbackPayload)), diag);
        assertTrue(diag.contains("used=true"), diag);
    }

    @Test
    void stageCountsSummarizeRawKeysAndValues() throws Exception {
        String rawStageKey = "private stage key should not remain";
        String rawStageValue = "private stage value should not remain";
        TraceStore.put("web.failsoft.stageCountsSelectedFromOut", Map.of(
                "OFFICIAL", 2,
                rawStageKey, rawStageValue));

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawStageKey), diag);
        assertFalse(diag.contains(rawStageValue), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawStageKey)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawStageValue)), diag);
        assertTrue(diag.contains("OFFICIAL=2"), diag);
    }

    @Test
    void webFailSoftDiagnosticsExposeLadderKpisWithoutRawQuery() throws Exception {
        String rawQuery = "private ladder query should not remain";
        TraceStore.put("web.failsoft.executedQuery", rawQuery);
        TraceStore.put("web.failsoft.executedQueryHash", SafeRedactor.hashValue(rawQuery));
        TraceStore.put("tracePool.size", 6);
        TraceStore.put("rescueMerge.used", true);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tracePool.size: 6"), diag);
        assertTrue(diag.contains("rescueMerge.used: true"), diag);
        assertFalse(diag.contains(rawQuery), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawQuery)), diag);
    }

    @Test
    void webFailSoftDiagnosticsExposeNamespacedRescueKpisWithoutRawQuery() throws Exception {
        String rawQuery = "private namespaced rescue query should not remain";
        TraceStore.put("web.failsoft.executedQuery", rawQuery);
        TraceStore.put("web.failsoft.executedQueryHash", SafeRedactor.hashValue(rawQuery));
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 4);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tracePool.size: 4"), diag);
        assertTrue(diag.contains("rescueMerge.used: true"), diag);
        assertFalse(diag.contains(rawQuery), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawQuery)), diag);
    }

    @Test
    void webFailSoftDiagnosticsExposeVectorFallbackKpisWithoutRawQuery() throws Exception {
        String rawQuery = "private vector fallback query should not remain";
        TraceStore.put("web.failsoft.executedQuery", rawQuery);
        TraceStore.put("web.failsoft.executedQueryHash", SafeRedactor.hashValue(rawQuery));
        TraceStore.put("vectorFallback.used", true);
        TraceStore.put("vectorFallback.reason", "web_empty");
        TraceStore.put("vectorFallback.effectiveTopK", 10);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("vectorFallback.used: true"), diag);
        assertTrue(diag.contains("vectorFallback.reason: `web_empty`"), diag);
        assertTrue(diag.contains("vectorFallback.effectiveTopK: 10"), diag);
        assertFalse(diag.contains(rawQuery), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawQuery)), diag);
    }

    @Test
    void correlationTraceLabelsFreeFormIdentifiers() throws Exception {
        String rawSessionId = "private session id should not remain";
        TraceStore.put("sid", rawSessionId);
        TraceStore.put("x-request-id", "rid-123");

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawSessionId), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawSessionId)), diag);
        assertTrue(diag.contains("rid-123"), diag);
    }

    @Test
    void cancellationDiagnosticsDoNotAssumeSoftCancelOnlyComesFromDelegateThrows() throws Exception {
        TraceStore.put("ops.cancelShield.cancelTrue.count", 1);
        TraceStore.put("ops.cancelShield.downgraded.count", 1);
        TraceStore.put("ops.cancelShield.softCancelled.count", 1);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("softCancelled"), diag);
        assertFalse(diag.contains("delegate threw"), diag);
    }

    @Test
    void planDiagnosticsTraceLabelsFreeFormIdentifiers() throws Exception {
        String rawPlanId = "private plan id should not remain";
        String rawPlanOrder = "private plan order should not remain";
        TraceStore.put("plan.id", rawPlanId);
        TraceStore.put("plan.order", rawPlanOrder);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawPlanId), diag);
        assertFalse(diag.contains(rawPlanOrder), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawPlanId)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawPlanOrder)), diag);

        TraceStore.clear();
        TraceStore.put("plan.id", "plan-123");
        assertTrue(diagnostics(aspect).contains("plan-123"));
    }

    @Test
    void modeDiagnosticsTraceLabelsFreeFormScalarValues() throws Exception {
        String rawMode = "private orchestration mode should not remain";
        String rawEngine = "private await engine should not remain";
        String rawPool = "private pool name should not remain";
        TraceStore.put("orch.mode", rawMode);
        TraceStore.put("web.await.root.engine", rawEngine);
        TraceStore.put("web.failsoft.starvationFallback.poolUsed", rawPool);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawMode), diag);
        assertFalse(diag.contains(rawEngine), diag);
        assertFalse(diag.contains(rawPool), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawMode)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawEngine)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawPool)), diag);

        TraceStore.clear();
        TraceStore.put("orch.mode", "NORMAL");
        TraceStore.put("web.await.root.engine", "brave");
        TraceStore.put("web.failsoft.starvationFallback.poolUsed", "cache_only");
        String compact = diagnostics(aspect);
        assertTrue(compact.contains("NORMAL"), compact);
        assertTrue(compact.contains("brave"), compact);
        assertTrue(compact.contains("cache_only"), compact);
    }

    @Test
    void providerScalarDiagnosticsTraceLabelsFreeFormValues() throws Exception {
        String rawHardDown = "private hard down flag should not remain";
        String rawBraveCount = "private brave skipped count should not remain";
        String rawAwaitWaitedMs = "private await waited ms should not remain";
        TraceStore.put("web.hardDown", rawHardDown);
        TraceStore.put("web.brave.skipped.count", rawBraveCount);
        TraceStore.put("web.await.root.waitedMs", rawAwaitWaitedMs);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertFalse(diag.contains(rawHardDown), diag);
        assertFalse(diag.contains(rawBraveCount), diag);
        assertFalse(diag.contains(rawAwaitWaitedMs), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawHardDown)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawBraveCount)), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawAwaitWaitedMs)), diag);

        TraceStore.clear();
        TraceStore.put("web.hardDown", true);
        TraceStore.put("web.brave.skipped.count", 2);
        TraceStore.put("web.await.root.waitedMs", 150);
        String numeric = diagnostics(aspect);
        assertTrue(numeric.contains("web.hardDown: true"), numeric);
        assertTrue(numeric.contains("count=2"), numeric);
        assertTrue(numeric.contains("waitedMs=150"), numeric);
    }

    @Test
    void providerDiagnosticsExposeSerpApiSkippedReasonWithoutRawReason() throws Exception {
        String rawReason = "private serpapi skipped reason api_key=secret should not remain";
        TraceStore.put("web.serpapi.skipped.reason", rawReason);
        TraceStore.put("web.serpapi.skipped.count", 1);
        TraceStore.put("web.serpapi.skipped.extraMs", 25);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("serpapi.skipped"), diag);
        assertTrue(diag.contains("count=1"), diag);
        assertTrue(diag.contains("extraMs=25"), diag);
        assertFalse(diag.contains(rawReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeTavilySkippedReasonWithoutRawReason() throws Exception {
        String rawReason = "private tavily skipped reason api_key=secret should not remain";
        TraceStore.put("web.tavily.skipped.reason", rawReason);
        TraceStore.put("web.tavily.skipped.count", 1);
        TraceStore.put("web.tavily.skipped.extraMs", 25);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tavily.skipped"), diag);
        assertTrue(diag.contains("count=1"), diag);
        assertTrue(diag.contains("extraMs=25"), diag);
        assertFalse(diag.contains(rawReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeSerpApiDisabledStatusWithoutRawReason() throws Exception {
        String rawDisabledReason = "private serpapi disabled reason api_key=secret should not remain";
        TraceStore.put("web.serpapi.providerDisabled", true);
        TraceStore.put("web.serpapi.disabledReason", rawDisabledReason);
        TraceStore.put("web.serpapi.failureReason", "provider-disabled");
        TraceStore.put("web.serpapi.returnedCount", 0);
        TraceStore.put("web.serpapi.afterFilterCount", 0);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("serpapi.status"), diag);
        assertTrue(diag.contains("providerDisabled=true"), diag);
        assertTrue(diag.contains("failureReason=`provider-disabled`"), diag);
        assertTrue(diag.contains("returnedCount=0"), diag);
        assertTrue(diag.contains("afterFilterCount=0"), diag);
        assertFalse(diag.contains(rawDisabledReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawDisabledReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeTavilyDisabledStatusWithoutRawReason() throws Exception {
        String rawDisabledReason = "private tavily disabled reason api_key=secret should not remain";
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", rawDisabledReason);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tavily.status"), diag);
        assertTrue(diag.contains("providerDisabled=true"), diag);
        assertTrue(diag.contains("failureReason=`provider-disabled`"), diag);
        assertTrue(diag.contains("returnedCount=0"), diag);
        assertTrue(diag.contains("afterFilterCount=0"), diag);
        assertFalse(diag.contains(rawDisabledReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawDisabledReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeBraveDisabledStatusWithoutRawReason() throws Exception {
        String rawDisabledReason = "private brave disabled reason api_key=secret should not remain";
        TraceStore.put("web.brave.providerDisabled", true);
        TraceStore.put("web.brave.disabledReason", rawDisabledReason);
        TraceStore.put("web.brave.failureReason", "provider-disabled");
        TraceStore.put("web.brave.returnedCount", 0);
        TraceStore.put("web.brave.afterFilterCount", 0);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("brave.status"), diag);
        assertTrue(diag.contains("providerDisabled=true"), diag);
        assertTrue(diag.contains("failureReason=`provider-disabled`"), diag);
        assertTrue(diag.contains("returnedCount=0"), diag);
        assertTrue(diag.contains("afterFilterCount=0"), diag);
        assertFalse(diag.contains(rawDisabledReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawDisabledReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeNaverDisabledStatusWithoutRawReason() throws Exception {
        String rawDisabledReason = "private naver disabled reason client_secret=secret should not remain";
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", rawDisabledReason);
        TraceStore.put("web.naver.failureReason", "provider-disabled");
        TraceStore.put("web.naver.returnedCount", 0);
        TraceStore.put("web.naver.afterFilterCount", 0);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("naver.status"), diag);
        assertTrue(diag.contains("providerDisabled=true"), diag);
        assertTrue(diag.contains("failureReason=`provider-disabled`"), diag);
        assertTrue(diag.contains("returnedCount=0"), diag);
        assertTrue(diag.contains("afterFilterCount=0"), diag);
        assertFalse(diag.contains(rawDisabledReason), diag);
        assertTrue(diag.contains(SafeRedactor.hashValue(rawDisabledReason)), diag);
    }

    @Test
    void providerDiagnosticsExposeNaverObservedTrueZeroStages() throws Exception {
        TraceStore.put("web.naver.failureClass", "TRUE_ZERO");
        TraceStore.put("web.naver.providerAttemptObserved", true);
        TraceStore.put("web.naver.providerResultCount", 0);
        TraceStore.put("web.naver.preFilterCount", 0);
        TraceStore.put("web.naver.postFilterCount", 0);
        TraceStore.put("web.naver.mergeCount", "unknown");

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("naver.status"), diag);
        assertTrue(diag.contains("failureClass=`TRUE_ZERO`"), diag);
        assertTrue(diag.contains("providerAttemptObserved=true"), diag);
        assertTrue(diag.contains("providerResultCount=0"), diag);
        assertTrue(diag.contains("preFilterCount=0"), diag);
        assertTrue(diag.contains("postFilterCount=0"), diag);
        assertTrue(diag.contains("mergeCount=unknown"), diag);
    }

    @Test
    void providerDiagnosticsBoundMalformedNaverStageValuesToUnknown() throws Exception {
        String rawFailureClass = "private failure class client_secret=secret should not remain";
        String rawCount = "private count api_key=secret should not remain";
        TraceStore.put("web.naver.failureClass", rawFailureClass);
        TraceStore.put("web.naver.providerAttemptObserved", rawCount);
        TraceStore.put("web.naver.providerResultCount", -1);
        TraceStore.put("web.naver.preFilterCount", Double.NaN);
        TraceStore.put("web.naver.postFilterCount", Map.of("raw", rawCount));
        TraceStore.put("web.naver.mergeCount", rawCount);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("failureClass=`" + SafeRedactor.hashValue(rawFailureClass) + "`"), diag);
        assertTrue(diag.contains("providerAttemptObserved=unknown"), diag);
        assertTrue(diag.contains("providerResultCount=unknown"), diag);
        assertTrue(diag.contains("preFilterCount=unknown"), diag);
        assertTrue(diag.contains("postFilterCount=unknown"), diag);
        assertTrue(diag.contains("mergeCount=unknown"), diag);
        assertFalse(diag.contains(rawFailureClass), diag);
        assertFalse(diag.contains(rawCount), diag);
    }

    @Test
    void providerDiagnosticsDoNotCoerceMissingNaverStagesToZero() throws Exception {
        TraceStore.put("web.naver.failureClass", "TRUE_ZERO");

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("failureClass=`TRUE_ZERO`"), diag);
        assertFalse(diag.contains("providerAttemptObserved="), diag);
        assertFalse(diag.contains("providerResultCount="), diag);
        assertFalse(diag.contains("preFilterCount="), diag);
        assertFalse(diag.contains("postFilterCount="), diag);
        assertFalse(diag.contains("mergeCount="), diag);
    }

    @Test
    void providerDiagnosticsExposeNaverFailureTimingStatus() throws Exception {
        TraceStore.put("web.naver.failureReason", "rate-limit");
        TraceStore.put("web.naver.httpStatus", 429);
        TraceStore.put("web.naver.rateLimited", true);
        TraceStore.put("web.naver.timeout", false);
        TraceStore.put("web.naver.tookMs", 321);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("naver.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("httpStatus=429"), diag);
        assertTrue(diag.contains("rateLimited=true"), diag);
        assertTrue(diag.contains("timeout=false"), diag);
        assertTrue(diag.contains("tookMs=321"), diag);
    }

    @Test
    void providerDiagnosticsExposeNaverCooldownWithoutRawReason() throws Exception {
        String rawCooldownReason = "private naver cooldown reason client_secret=secret should not remain";
        TraceStore.put("web.naver.failureReason", "rate-limit");
        TraceStore.put("web.naver.cooldown.reason", rawCooldownReason);
        TraceStore.put("web.naver.retryAfterMs", 9000);
        TraceStore.put("web.naver.cooldown.hintMs", 9000);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("naver.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("cooldownReason=`" + SafeRedactor.hashValue(rawCooldownReason) + "`"), diag);
        assertTrue(diag.contains("retryAfterMs=9000"), diag);
        assertTrue(diag.contains("cooldownHintMs=9000"), diag);
        assertFalse(diag.contains(rawCooldownReason), diag);
    }

    @Test
    void providerDiagnosticsExposeSerpApiFailureTimingStatus() throws Exception {
        TraceStore.put("web.serpapi.failureReason", "rate-limit");
        TraceStore.put("web.serpapi.httpStatus", 429);
        TraceStore.put("web.serpapi.rateLimited", true);
        TraceStore.put("web.serpapi.timeout", false);
        TraceStore.put("web.serpapi.tookMs", 123);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("serpapi.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("httpStatus=429"), diag);
        assertTrue(diag.contains("rateLimited=true"), diag);
        assertTrue(diag.contains("timeout=false"), diag);
        assertTrue(diag.contains("tookMs=123"), diag);
    }

    @Test
    void providerDiagnosticsExposeTavilyFailureTimingStatus() throws Exception {
        TraceStore.put("web.tavily.failureReason", "rate-limit");
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.rateLimited", true);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.tookMs", 123);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tavily.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("httpStatus=429"), diag);
        assertTrue(diag.contains("rateLimited=true"), diag);
        assertTrue(diag.contains("timeout=false"), diag);
        assertTrue(diag.contains("tookMs=123"), diag);
    }

    @Test
    void providerDiagnosticsExposeSerpApiCooldownWithoutRawReason() throws Exception {
        String rawCooldownReason = "private serpapi cooldown reason api_key=secret should not remain";
        TraceStore.put("web.serpapi.failureReason", "rate-limit");
        TraceStore.put("web.serpapi.cooldown.reason", rawCooldownReason);
        TraceStore.put("web.serpapi.retryAfterMs", 13000);
        TraceStore.put("web.serpapi.cooldown.hintMs", 13000);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("serpapi.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("cooldownReason=`" + SafeRedactor.hashValue(rawCooldownReason) + "`"), diag);
        assertTrue(diag.contains("retryAfterMs=13000"), diag);
        assertTrue(diag.contains("cooldownHintMs=13000"), diag);
        assertFalse(diag.contains(rawCooldownReason), diag);
    }

    @Test
    void providerDiagnosticsExposeTavilyCooldownWithoutRawReason() throws Exception {
        String rawCooldownReason = "private tavily cooldown reason api_key=secret should not remain";
        TraceStore.put("web.tavily.failureReason", "rate-limit");
        TraceStore.put("web.tavily.cooldown.reason", rawCooldownReason);
        TraceStore.put("web.tavily.retryAfterMs", 13000);
        TraceStore.put("web.tavily.cooldown.hintMs", 13000);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("tavily.status"), diag);
        assertTrue(diag.contains("failureReason=`rate-limit`"), diag);
        assertTrue(diag.contains("cooldownReason=`" + SafeRedactor.hashValue(rawCooldownReason) + "`"), diag);
        assertTrue(diag.contains("retryAfterMs=13000"), diag);
        assertTrue(diag.contains("cooldownHintMs=13000"), diag);
        assertFalse(diag.contains(rawCooldownReason), diag);
    }

    @Test
    void diagnosticsExposeSerpApiAndTavilyAwaitTimeoutSummaryCounts() throws Exception {
        TraceStore.put("web.await.events.summary.engine.SerpApi.cause.await_timeout.count", 2L);
        TraceStore.put("web.await.events.summary.engine.Tavily.cause.await_timeout.count", 3L);

        EvidenceListTraceInjectionAspect aspect = new EvidenceListTraceInjectionAspect(new MockEnvironment());

        String diag = diagnostics(aspect);

        assertTrue(diag.contains("web.await.timeout.counts: N=0 B=0 S=2 T=3"), diag);
    }

    private static String inject(EvidenceListTraceInjectionAspect aspect, String base, String diag) throws Exception {
        Method method = EvidenceListTraceInjectionAspect.class.getDeclaredMethod("inject", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(aspect, base, diag);
    }

    private static String diagnostics(EvidenceListTraceInjectionAspect aspect) throws Exception {
        Method method = EvidenceListTraceInjectionAspect.class.getDeclaredMethod("buildDiagnosticsBlock");
        method.setAccessible(true);
        return (String) method.invoke(aspect);
    }
}
