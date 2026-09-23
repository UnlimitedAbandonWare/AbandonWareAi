package com.example.lms.service.trace;

import com.example.lms.service.NaverSearchService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.attribution.TraceAblationAttributionResult;
import com.example.lms.trace.attribution.TraceAblationAttributionService;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceHtmlBuilderRedactionTest {

    @Test
    void panelHeaderRenderingIsOwnedByLayoutHelper() throws Exception {
        Path builderPath = Path.of("main/java/com/example/lms/service/trace/TraceHtmlBuilder.java");
        Path helperPath = Path.of("main/java/com/example/lms/service/trace/TraceHtmlLayout.java");

        String builderSource = Files.readString(builderPath);

        assertTrue(Files.exists(helperPath), "TraceHtmlLayout helper should own reusable HTML layout fragments");
        String helperSource = Files.readString(helperPath);
        assertTrue(builderSource.contains("TraceHtmlLayout.renderPanelHeader("));
        assertFalse(builderSource.contains("private static String renderPanelHeader("));
        assertTrue(helperSource.contains("static String renderPanelHeader"));
        assertTrue(helperSource.contains("replace(\"&\", \"&amp;\")"));
    }

    @Test
    void numericTraceHtmlBuilderParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/trace/TraceHtmlBuilder.java"))
                .replace("\r\n", "\n");

        assertParserCatchNarrowed(source, "return Long.parseLong(String.valueOf(v).trim());");
        assertParserCatchNarrowed(source, "return Integer.parseInt(String.valueOf(v).trim());");
        assertTrue(source.contains("Trace ablation callout suppressed errorType={0}"));
        assertTrue(source.contains("Trace HTML numeric fallback targetType={0} valueLength={1} errorType={2}"));
        assertFalse(source.contains("LOG.log(System.Logger.Level.DEBUG, String.valueOf(v)"));
    }

    @Test
    void numericTraceHtmlBuilderParsersRejectNonFiniteNumbers() throws Exception {
        Method toLong = TraceHtmlBuilder.class.getDeclaredMethod("toLong", Object.class);
        Method toInt = TraceHtmlBuilder.class.getDeclaredMethod("toInt", Object.class);
        toLong.setAccessible(true);
        toInt.setAccessible(true);

        assertEquals(null, toLong.invoke(null, Double.POSITIVE_INFINITY));
        assertEquals(null, toInt.invoke(null, Double.POSITIVE_INFINITY));
    }

    @Test
    void snapshotStyleRenderingIsOwnedByLayoutHelper() throws Exception {
        Path builderPath = Path.of("main/java/com/example/lms/service/trace/TraceHtmlBuilder.java");
        Path helperPath = Path.of("main/java/com/example/lms/service/trace/TraceHtmlLayout.java");

        String builderSource = Files.readString(builderPath);
        String helperSource = Files.readString(helperPath);

        assertTrue(builderSource.contains("TraceHtmlLayout.snapshotStyle()"));
        assertFalse(builderSource.contains("sb.append(\"<style>\")"));
        assertTrue(helperSource.contains("static String snapshotStyle"));
        assertTrue(helperSource.contains(".trace-risk-high"));
    }

    @Test
    void snapshotHtmlSanitizesRawTraceAndMdcInputs() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String rawQuery = "private html builder query must not remain";
        String rawSecret = "Bearer " + "html-builder-secret-token";
        String rawMdc = "raw html mdc session";

        String html = builder.buildSnapshotHtml(
                "snapshot-1",
                "2026-06-05T00:00:00Z",
                "sid-hash",
                "trace-hash",
                "request-hash",
                "unit_test",
                "GET",
                "/api/chat",
                500,
                null,
                Map.of(
                        "web.failsoft.soakKpiJson.runId.1",
                        "{\"query\":\"" + rawQuery + "\",\"authorization\":\"" + rawSecret + "\"}",
                        "detail",
                        rawQuery),
                Map.of("sid", rawMdc));

        assertFalse(html.contains(rawQuery));
        assertFalse(html.contains(rawSecret));
        assertFalse(html.contains(rawMdc));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void snapshotHtmlSanitizesSensitiveTraceKeyLabels() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String rawOwnerToken = "owner-token-must-not-leak";
        String rawApiKey = "api-key-must-not-leak";

        String html = builder.buildSnapshotHtml(
                "snapshot-sensitive-keys",
                "2026-06-05T00:00:00Z",
                "sid-hash",
                "trace-hash",
                "request-hash",
                "unit_test",
                "GET",
                "/api/chat",
                200,
                null,
                Map.of(
                        "ownerToken", rawOwnerToken,
                        "web.naver.apiKey", rawApiKey,
                        "web.naver.returnedCount", 3),
                Map.of());

        assertTrue(html.contains("web.naver.returnedCount"));
        assertFalse(html.contains("ownerToken"), html);
        assertFalse(html.contains("apiKey"), html);
        assertFalse(html.contains(rawOwnerToken), html);
        assertFalse(html.contains(rawApiKey), html);
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    private static void assertParserCatchNarrowed(String source, String parserCall) {
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, () -> "parser call should be locatable: " + parserCall);
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "TraceHtmlBuilder numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "TraceHtmlBuilder numeric parser fallback should catch only NumberFormatException");
    }

    @Test
    void snapshotHtmlSanitizesDirectSnapshotMetaInputs() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String rawReason = "private snapshot reason owner_token=html-owner-secret";
        String rawPath = "/api/chat?api_key=test-fixture&query=private-path-query";
        String rawError = "stack failed for private payload password=html-secret";

        String html = builder.buildSnapshotHtml(
                "snapshot-2",
                "2026-06-05T00:00:00Z",
                "raw-session-id-for-html",
                "raw-trace-id-for-html",
                "raw-request-id-for-html",
                rawReason,
                "POST",
                rawPath,
                500,
                rawError,
                Map.of(),
                Map.of());

        assertFalse(html.contains(rawReason));
        assertFalse(html.contains(rawPath));
        assertFalse(html.contains(rawError));
        assertFalse(html.contains("html-owner-secret"));
        assertFalse(html.contains("sk-html-builder-secret"));
        assertFalse(html.contains("private-path-query"));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesTraceAblationSelfAskBeamText() {
        String rawQuestion = "private taa self ask question for customer retry plan";
        String rawAnswer = "private taa self ask answer owner_token=taa-owner-secret";
        TraceAblationAttributionService attributionService = new TraceAblationAttributionService() {
            @Override
            public TraceAblationAttributionResult analyze(
                    Map<String, Object> trace,
                    List<Content> webTopK,
                    List<Content> vectorTopK) {
                return new TraceAblationAttributionResult(
                        "test",
                        "STRIKE",
                        0.75,
                        List.of(new TraceAblationAttributionResult.Contributor(
                                "private-contributor",
                                "test",
                                "private title",
                                1.0,
                                1.0,
                                List.of("evidence"),
                                List.of("recommendation"))),
                        List.of(new TraceAblationAttributionResult.Beam(
                                1.0,
                                1.0,
                                List.of(new TraceAblationAttributionResult.QaStep(
                                        rawQuestion,
                                        rawAnswer,
                                        1.0,
                                        List.of())))),
                        Map.of());
            }
        };
        TraceHtmlBuilder builder = new TraceHtmlBuilder(attributionService);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("orch.mode", "STRIKE"));

        assertTrue(html.contains("Self-Ask beams"));
        assertFalse(html.contains(rawQuestion));
        assertFalse(html.contains(rawAnswer));
        assertFalse(html.contains("taa-owner-secret"));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesTraceAblationEvidenceLists() {
        String rawEvidence = "private taa contributor evidence from user prompt";
        String rawRecommendation = "private taa fix hint owner_token=taa-list-secret";
        TraceAblationAttributionService attributionService = new TraceAblationAttributionService() {
            @Override
            public TraceAblationAttributionResult analyze(
                    Map<String, Object> trace,
                    List<Content> webTopK,
                    List<Content> vectorTopK) {
                return new TraceAblationAttributionResult(
                        "test",
                        "STRIKE",
                        0.75,
                        List.of(new TraceAblationAttributionResult.Contributor(
                                "private-contributor",
                                "test",
                                "private title",
                                1.0,
                                1.0,
                                List.of(rawEvidence),
                                List.of(rawRecommendation))),
                        List.of(),
                        Map.of());
            }
        };
        TraceHtmlBuilder builder = new TraceHtmlBuilder(attributionService);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("orch.mode", "STRIKE"));

        assertTrue(html.contains("Trace-Ablation Attribution"));
        assertFalse(html.contains(rawEvidence));
        assertFalse(html.contains(rawRecommendation));
        assertFalse(html.contains("taa-list-secret"));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelAblationSuppressionLeavesTraceBreadcrumbWithoutRawError() {
        TraceStore.clear();
        TraceAblationAttributionService attributionService = new TraceAblationAttributionService() {
            @Override
            public TraceAblationAttributionResult analyze(
                    Map<String, Object> trace,
                    List<Content> webTopK,
                    List<Content> vectorTopK) {
                throw new IllegalStateException("private ablation owner_token=taa-secret");
            }
        };
        TraceHtmlBuilder builder = new TraceHtmlBuilder(attributionService);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("orch.mode", "STRIKE"));

        assertTrue(html.contains("Orchestration State"));
        assertEquals(Boolean.TRUE, TraceStore.get("traceHtml.ablation.suppressed.render"));
        assertEquals("IllegalStateException", TraceStore.get("traceHtml.ablation.suppressed.render.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("owner_token"), trace);
        assertFalse(trace.contains("taa-secret"), trace);
        TraceStore.clear();
    }

    @Test
    void splitPanelSanitizesRawSearchTraceDebugFields() {
        String rawSuffix = "private suffix from user query neighborhood";
        String rawOrg = "private org canonical from user question";
        String rawSiteFilter = "site:private.example.test private customer context";
        String rawDomainReason = "private domain disable reason from operator note";
        String rawKeywordReason = "private keyword disable reason owner_token=keyword-owner-secret";
        NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
        trace.domainFilterEnabled = false;
        trace.keywordFilterEnabled = false;
        trace.reasonDomainFilterDisabled = rawDomainReason;
        trace.reasonKeywordFilterDisabled = rawKeywordReason;
        trace.suffixApplied = rawSuffix;
        trace.orgResolved = true;
        trace.orgCanonical = rawOrg;
        trace.siteFiltersApplied.add(rawSiteFilter);

        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);
        String html = builder.buildSplitPanel(
                trace,
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "dbg.search.enabled", true,
                        "dbg.search.boost.active", true));

        assertTrue(html.contains("Web Trace"));
        assertFalse(html.contains(rawSuffix));
        assertFalse(html.contains(rawOrg));
        assertFalse(html.contains(rawSiteFilter));
        assertFalse(html.contains(rawDomainReason));
        assertFalse(html.contains(rawKeywordReason));
        assertFalse(html.contains("keyword-owner-secret"));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesModelRouterEventReasons() {
        String rawReason = "private model router reason for customer prompt route note";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "ml.router.events",
                        List.of(Map.of(
                                "event", "blocked",
                                "reason", rawReason,
                                "selected", "low",
                                "requestedModel", "gpt-test"))));

        assertTrue(html.contains("Model Router Events"));
        assertFalse(html.contains(rawReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesRagPipelineEventReasons() {
        String rawFailureReason = "private pipeline failure reason for customer retrieval note";
        String rawControlReason = "private pipeline control reason from user prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "orch.events.v1",
                        List.of(Map.of(
                                "phase", "retrieve",
                                "stage", "web",
                                "step", "merge",
                                "status", "failed",
                                "failure", Map.of("reasonCode", rawFailureReason),
                                "control", Map.of(
                                        "action", "fallback",
                                        "reasonCode", rawControlReason,
                                        "applied", true),
                                "output", Map.of(
                                        "returnedCount", 0,
                                        "afterFilterCount", 0,
                                        "selectedCount", 0,
                                        "stageMs", 12)))));

        assertTrue(html.contains("RAG Pipeline Events"));
        assertFalse(html.contains(rawFailureReason));
        assertFalse(html.contains(rawControlReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesCollapsedSummaryQuery() {
        String rawQuery = "private collapsed summary query from user prompt";
        NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
        trace.query = rawQuery;
        trace.provider = "naver";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                trace,
                List.of(),
                List.of(),
                List.of(),
                Map.of());

        assertTrue(html.contains("Search Trace"));
        assertFalse(html.contains(rawQuery));
        assertTrue(html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesNaverPlanHintSkippedReason() {
        String rawReason = "private plan hint skipped reason from user location prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "web.naver.planHintBoostOnly.decision", "skipped",
                        "web.naver.planHintBoostOnly.skipped.reason", rawReason));

        assertTrue(html.contains("Naver PlanHint Boost-Only Overlay"));
        assertFalse(html.contains(rawReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesOrchestrationReason() {
        String rawReason = "private orchestration bypass reason from user prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "orch.mode", "BYPASS",
                        "orch.bypass", true,
                        "orch.reason", rawReason));

        assertTrue(html.contains("Mode"));
        assertFalse(html.contains(rawReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesWebFailSoftReasons() {
        String tradeoffReason = "private cooldown tradeoff reason from user prompt";
        String naverReason = "private naver skipped reason from customer request";
        String braveReason = "private brave skipped reason from customer request";
        String serpapiReason = "private serpapi disabled reason api_key=test-fixture";
        String rescueReason = "private min citation rescue block reason";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.ofEntries(
                        Map.entry("web.failsoft.minCitationsRescue.preflight.deficit", 1),
                        Map.entry("web.failsoft.rateLimitBackoff.max.remainingMs", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.max.delayMs", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.skipped.cooldown.count", 1),
                        Map.entry("web.failsoft.rateLimitBackoff.cooldownTradeoff.class", "budget"),
                        Map.entry("web.failsoft.rateLimitBackoff.cooldownTradeoff.reason", tradeoffReason),
                        Map.entry("web.failsoft.rateLimitBackoff.awaitTimeoutReconciledApplyTimes", 0),
                        Map.entry("web.failsoft.minCitationsRescue.preflight.needed", 0),
                        Map.entry("web.failsoft.minCitationsRescue.preflight.citeableCount", 0),
                        Map.entry("web.failsoft.minCitationsRescue.preflight.candidates.count", 0),
                        Map.entry("web.failsoft.minCitationsRescue.insertedCount", 0),
                        Map.entry("web.naver.skipped.reason", naverReason),
                        Map.entry("web.naver.providerDisabled", false),
                        Map.entry("web.naver.disabledReason", ""),
                        Map.entry("web.naver.failureReason", "cache-only-rescue"),
                        Map.entry("web.naver.rateLimited", false),
                        Map.entry("web.naver.retryAfterMs", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.naver.remainingMs", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.naver.last.streak", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.naver.last.delayMs", 0),
                        Map.entry("web.brave.skipped.reason", braveReason),
                        Map.entry("web.brave.providerDisabled", true),
                        Map.entry("web.brave.disabledReason", "missing_brave_api_key"),
                        Map.entry("web.brave.failureReason", "rate-limit"),
                        Map.entry("web.brave.rateLimited", true),
                        Map.entry("web.brave.retryAfterMs", 2500),
                        Map.entry("web.failsoft.rateLimitBackoff.brave.remainingMs", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.brave.last.streak", 0),
                        Map.entry("web.failsoft.rateLimitBackoff.brave.last.delayMs", 0),
                        Map.entry("web.serpapi.skipped.reason", serpapiReason),
                        Map.entry("web.serpapi.disabledReason", serpapiReason),
                        Map.entry("web.serpapi.failureReason", "provider-disabled"),
                        Map.entry("web.serpapi.providerDisabled", true),
                        Map.entry("web.failsoft.minCitationsRescue.blockReason", rescueReason)));

        assertTrue(html.contains("Web FailSoft Risk"));
        assertTrue(html.contains("<b>naver</b>"));
        assertTrue(html.contains("failure=cache-only-rescue"));
        assertTrue(html.contains("<b>brave</b>"));
        assertTrue(html.contains("disabled=true/missing_brave_api_key"));
        assertTrue(html.contains("failure=rate-limit"));
        assertTrue(html.contains("retryAfterMs=2500"));
        assertTrue(html.contains("<b>serpapi</b>"));
        assertFalse(html.contains(tradeoffReason));
        assertFalse(html.contains(naverReason));
        assertFalse(html.contains(braveReason));
        assertFalse(html.contains(serpapiReason));
        assertFalse(html.contains("api_key=test-fixture"));
        assertFalse(html.contains(rescueReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelMarksSerpApiAndTavilyRateLimitAsWarnRisk() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "web.serpapi.429", true,
                        "web.tavily.skippedByBreaker", true));

        assertTrue(html.contains("trace-risk-warn"), html);
    }

    @Test
    void splitPanelSanitizesWebFailSoftRunCandidateDropReason() {
        String rawQuery = "private failsoft run query from user prompt";
        String rawDropReason = "private candidate drop reason from user prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("web.failsoft.runs", List.of(Map.of(
                        "runId", "run-1",
                        "executedQuery", rawQuery,
                        "outCount", 0,
                        "candidates", List.of(Map.of(
                                "idx", 1,
                                "dropReason", rawDropReason))))));

        assertTrue(html.contains("Web FailSoft Runs"));
        assertFalse(html.contains(rawQuery));
        assertFalse(html.contains(rawDropReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesCtxPropagationMissingEventReason() {
        String rawReason = "private ctx propagation missing reason from user prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("ctx.propagation.missing.events", List.of(Map.of(
                        "kind", "missing",
                        "where", "chat",
                        "reason", rawReason))));

        assertTrue(html.contains("Context Propagation Events"));
        assertFalse(html.contains(rawReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesRelationSliceSelectionReason() {
        String rawReason = "private relation thumbnail selection reason from user prompt";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "retrieval.kg.relationThumbnail.sliceMapCount", 1,
                        "retrieval.kg.relationThumbnail.sliceMap", List.of(Map.of(
                                "rank", 1,
                                "hash", "hash:abc",
                                "relationKind", "related",
                                "selectionReason", rawReason))));

        assertTrue(html.contains("Relation Thumbnail Slice Map"));
        assertFalse(html.contains(rawReason));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesWebAwaitEventDetail() {
        String rawDetail = "private web await detail from user prompt";
        String rawError = "private web await errMsg owner_token=await-secret";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("web.await.events", List.of(Map.of(
                        "stage", "hard",
                        "engine", "naver",
                        "step", "search",
                        "cause", "timeout_hard",
                        "timeout", true,
                        "detail", rawDetail,
                        "errMsg", rawError))));

        assertTrue(html.contains("Web Await Events"));
        assertFalse(html.contains(rawDetail));
        assertFalse(html.contains(rawError));
        assertFalse(html.contains("await-secret"));
        assertTrue(html.contains("hash:") || html.contains("hash12"));
    }

    @Test
    void splitPanelSanitizesOrchestrationPartsSummaryAndRows() {
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "orch.parts.summary", "mode=NORMAL " + bearer,
                        "orch.parts.table", List.of(Map.of(
                                "phase", "RETRIEVE",
                                "detail", "web " + queryKey))));

        assertTrue(html.contains("Parts Build-up"));
        assertFalse(html.contains(bearer));
        assertFalse(html.contains(queryKey));
    }

    @Test
    void splitPanelSanitizesPromptEventLabelsAndPayload() {
        String bearer = "Bearer " + "local-placeholder-token";
        String queryKey = "api_" + "key=hidden-secret";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("prompt.events", List.of(Map.of(
                        "seq", 1,
                        "step", "compose " + bearer,
                        "intent", "private " + queryKey,
                        "domain", "rag " + bearer,
                        "payload", "raw " + queryKey))));

        assertTrue(html.contains("prompt.events"));
        assertFalse(html.contains(bearer));
        assertFalse(html.contains(queryKey));
    }

    @Test
    void splitPanelShowsSerpApiAndTavilyAwaitCooldownPills() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "web.failsoft.rateLimitBackoff.serpapi.awaitTimeoutApplied", true,
                        "web.failsoft.rateLimitBackoff.tavily.awaitTimeoutApplied", true));

        assertTrue(html.contains("AwaitCooldown:ST"));
    }

    @Test
    void splitPanelShowsSerpApiAndTavilyAwaitTimeoutCountPills() {
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "web.await.events.summary.engine.SerpApi.cause.await_timeout.count", 2L,
                        "web.await.events.summary.engine.Tavily.cause.await_timeout.count", 3L));

        assertTrue(html.contains("AwaitTimeout:N0 B0 S2 T3"));
    }

    @Test
    void splitPanelSanitizesPublicEvidenceFields() {
        String rawTitle = "private public evidence title owner_token=evidence-title-secret";
        String rawSource = "https://evidence.example.test/private?q=customer-query&owner_token=evidence-source-secret";
        String rawFilePath = "C:\\Users\\nninn\\secret\\customer-plan.md";
        TraceHtmlBuilder builder = new TraceHtmlBuilder(null);

        String html = builder.buildSplitPanel(
                new NaverSearchService.SearchTrace(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("rag.evidence.public", List.of(Map.of(
                        "marker", "W1",
                        "kind", "WEB",
                        "title", rawTitle,
                        "source", rawSource,
                        "filePath", rawFilePath,
                        "lineStart", 3,
                        "confidence", 0.7d))));

        assertTrue(html.contains("Citable Evidence"));
        assertFalse(html.contains(rawTitle));
        assertFalse(html.contains(rawSource));
        assertFalse(html.contains(rawFilePath));
        assertFalse(html.contains("ownerToken"));
        assertFalse(html.contains("customer-query"));
        assertTrue(html.contains("pathHash=" + com.example.lms.trace.SafeRedactor.hashValue(rawFilePath)));
        assertTrue(html.contains("pathLength=" + rawFilePath.length()));
        assertTrue(html.contains("hash:") || html.contains("hash12") || html.contains("evidence.example.test"));
    }
}
