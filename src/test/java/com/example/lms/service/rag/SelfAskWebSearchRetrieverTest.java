package com.example.lms.service.rag;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverSearchService.SearchResult;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.service.rag.query.SelfAskRewriteRiskScorer;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskWebSearchRetrieverTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void creativeRequestedTemperatureHonorsProfileBandAndMandatoryCap() {
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("llm.selfAsk.temperature.max", 0.91d);
        GuardContextHolder.set(context);

        assertEquals(0.91d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));

        context.setSensitiveTopic(true);
        assertEquals(0.2d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));
    }

    @Test
    void partialProfileCannotUnlockCreativeRetrieverTemperature() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        GuardContextHolder.set(context);

        assertEquals(0.2d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));
    }

    @Test
    void creativeSelfAskRequiresPrivacySafeContextAndRequestedHash() {
        GuardContext context = completeWildCreativeContext();
        context.getPlanOverrides().remove("creative.emergence.requestedOptionsHash");
        GuardContextHolder.set(context);

        assertEquals(0.2d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));

        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("privacy.boundary.enforce", true);
        assertEquals(0.2d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));
    }

    @Test
    void creativeRewriteRiskCannotLiftTemperatureAboveSubFloorMandatoryCap() {
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("llm.explore.temperature.max", 0.05d);
        GuardContextHolder.set(context);
        SelfAskWebSearchRetriever retriever = new SelfAskWebSearchRetriever(null, null, null, null);
        ReflectionTestUtils.setField(retriever, "riskRewriteEnabled", true);
        ReflectionTestUtils.setField(retriever, "riskRewriteMinTemperature", 0.12d);
        ReflectionTestUtils.setField(retriever, "riskRewriteMaxTemperature", 0.35d);

        double requested = SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d);
        SelfAskRewriteRiskScorer.Score score = ReflectionTestUtils.invokeMethod(
                retriever, "refreshRewriteRisk", "creative query", new java.util.HashMap<>(), 0, 2, requested);

        assertTrue(score.rewriteTemperatureWeighted() <= 0.05d);
        assertTrue(score.validationTemperature() <= 0.05d);
        assertTrue(score.explorationTemperature() <= 0.05d);
    }

    @Test
    void invalidCreativeSelfAskBandFailsSoftToLegacyTemperature() {
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.50d);
        GuardContextHolder.set(context);

        assertEquals(0.2d, SelfAskWebSearchRetriever.creativeRequestedRewriteTemperature(0.2d));
    }

    @Test
    void selfAskLlmPromptTemplatesStayInKeywordPromptBuilder() throws Exception {
        String retriever = Files.readString(Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        String promptBuilder = Files.readString(Path.of("main/java/com/example/lms/prompt/QueryKeywordPromptBuilder.java"));

        assertFalse(retriever.contains("private static final String SEARCH_PROMPT"));
        assertFalse(retriever.contains("private static final String FOLLOWUP_PROMPT"));
        assertFalse(retriever.contains("String prompt ="));
        assertFalse(retriever.contains("UserMessage.from(prompt)"));
        assertTrue(promptBuilder.contains("buildSelfAskSeedPrompt("));
        assertTrue(promptBuilder.contains("buildSelfAskFollowupPrompt("));
    }

    @Test
    void searchBudgetAccountingLivesOutsideRetrieverLargeFile() throws Exception {
        Path retrieverPath = Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java");
        Path budgetPath = Path.of("main/java/com/example/lms/service/rag/SelfAskSearchBudget.java");

        String retriever = Files.readString(retrieverPath);

        assertTrue(Files.exists(budgetPath), "SelfAsk search budget helper should reduce retriever file size");
        String budget = Files.readString(budgetPath);
        assertTrue(retriever.contains("SelfAskSearchBudget budget = new SelfAskSearchBudget("));
        assertFalse(retriever.contains("private static final class SearchBudget"));
        assertTrue(budget.contains("final class SelfAskSearchBudget"));
        assertTrue(budget.contains("boolean tryConsume()"));
        assertTrue(budget.contains("int remaining()"));
    }

    @Test
    void numericParsingLivesOutsideRetrieverLargeFile() throws Exception {
        Path retrieverPath = Path.of("main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java");
        Path numbersPath = Path.of("main/java/com/example/lms/service/rag/SelfAskNumbers.java");

        String retriever = Files.readString(retrieverPath);

        assertTrue(Files.exists(numbersPath), "SelfAsk numeric helpers should reduce retriever file size");
        String numbers = Files.readString(numbersPath);
        assertTrue(retriever.contains("SelfAskNumbers.parseDouble("));
        assertTrue(retriever.contains("SelfAskNumbers.parseLong("));
        assertTrue(retriever.contains("SelfAskNumbers.clampInt("));
        assertTrue(retriever.contains("SelfAskNumbers.clampDouble("));
        assertFalse(retriever.contains("private static double parseDouble("));
        assertFalse(retriever.contains("private static long parseLong("));
        assertFalse(retriever.contains("private static int clampInt("));
        assertFalse(retriever.contains("private static double clampDouble("));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.int\", ignore); }"));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.long\", ignore); }"));
        assertFalse(retriever.contains("catch (Exception ignore) { traceSuppressed(\"meta.double\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.int\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.long\", ignore); }"));
        assertTrue(retriever.contains("catch (NumberFormatException ignore) { traceSuppressed(\"meta.double\", ignore); }"));
        assertTrue(retriever.contains("TraceStore.put(\"selfask.suppressed.\" + safeStage + \".errorType\", errorType(ignored));"));
        assertTrue(retriever.contains("return ignored instanceof NumberFormatException ? \"invalid_number\""));
        assertTrue(numbers.contains("final class SelfAskNumbers"));
        assertFalse(numbers.contains("catch (Exception ignore)"));
        assertTrue(numbers.contains("catch (NumberFormatException ignore)"));
    }

    @Test
    void selfAskNumbersRejectNonFiniteValues() {
        assertEquals(0.25d, SelfAskNumbers.parseDouble(Double.POSITIVE_INFINITY, 0.25d), 0.0001d);
        assertEquals(0.50d, SelfAskNumbers.parseDouble("Infinity", 0.50d), 0.0001d);
        assertEquals(7L, SelfAskNumbers.parseLong(Double.POSITIVE_INFINITY, 7L));
    }

    @Test
    void selfAskMetadataParsersRejectNonFiniteNumbers() throws Exception {
        Method metaInt = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        Method metaLong = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaLong", Map.class, String.class, long.class);
        Method metaDouble = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "metaDouble", Map.class, String.class, double.class);
        metaInt.setAccessible(true);
        metaLong.setAccessible(true);
        metaDouble.setAccessible(true);

        Map<String, Object> meta = Map.of(
                "i", Double.POSITIVE_INFINITY,
                "l", Double.POSITIVE_INFINITY,
                "d", "Infinity");

        assertEquals(3, metaInt.invoke(null, meta, "i", 3));
        assertEquals(7L, metaLong.invoke(null, meta, "l", 7L));
        assertEquals(0.5d, (Double) metaDouble.invoke(null, meta, "d", 0.5d), 0.0001d);
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.int.errorType"));
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.long.errorType"));
        assertEquals("invalid_number", TraceStore.get("selfask.suppressed.meta.double.errorType"));
    }

    @Test
    void selfAskTraceNumericParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertParserCatchNarrowed(source, "private static double zero100LaneWeight(String lane)");
        assertParserCatchNarrowed(source, "private static int traceInt(String key, int defaultValue)");
        assertParserCatchNarrowed(source, "private static long traceLong(String key, long defaultValue)");
    }

    @Test
    void selfAskWebRetrieverFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertSelfAskWebStage(source, "refreshRewriteRisk.trace");
        assertSelfAskWebStage(source, "zero100.queryBurst.budget");
        assertSelfAskWebStage(source, "zero100.queryBurst.anchorDroppedCount");
        assertSelfAskWebStage(source, "zero100.queryBurst.seedTrace");
        assertSelfAskWebStage(source, "zero100.rollover.events");
        assertSelfAskWebStage(source, "zero100.branch.budgetRollover");
        assertSelfAskWebStage(source, "zero100.branch.callBudget");
        assertSelfAskWebInvalidNumberStage(source, "zero100LaneWeight");
        assertSelfAskWebInvalidNumberStage(source, "traceInt");
        assertSelfAskWebInvalidNumberStage(source, "traceLong");
        assertSelfAskWebStage(source, "traceRequeryAttempt");
        assertSelfAskWebStage(source, "traceRequerySummary");
        assertSelfAskWebStage(source, "putTraceMetadata");
        assertSelfAskWebStage(source, "pruneSelfAskSnippet");
    }

    private static void assertSelfAskWebStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskWebSearchRetriever] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing SelfAskWebSearchRetriever fail-soft stage: " + stage);
    }

    private static void assertSelfAskWebInvalidNumberStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[SelfAskWebSearchRetriever] fail-soft stage={} errorType={}\",")
                        && source.contains("\"" + stage + "\", \"invalid_number\""),
                () -> "missing SelfAskWebSearchRetriever invalid_number fail-soft stage: " + stage);
    }

    @Test
    void globalDisabledWithoutExplicitPlanOverrideDoesNotCallProvider() {
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                "simple query",
                Map.of("enableSelfAsk", "true")));

        assertTrue(out.isEmpty());
        assertEquals(0, provider.calls.get());
        assertEquals("global-disabled-no-plan-override", TraceStore.get("selfask.disabled.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.planOverride.enabled"));
    }

    @Test
    void globalDisabledWithExplicitPlanOverrideRunsFailSoftSearchPath() {
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                "short query",
                Map.of(
                        "selfask.enabled", "true",
                        "selfask.planOverride.reason", "expand.selfAsk.count",
                        "enableSelfAsk", "false")));

        assertEquals(1, provider.calls.get());
        assertTrue(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.planOverride.enabled"));
        assertEquals("expand.selfAsk.count", TraceStore.get("selfask.planOverride.reason"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} self-ask count={1}")
    @org.junit.jupiter.params.provider.CsvSource({"brave.v1,0", "brave.v1,1", "brave.v1,3", "brave.v1,4",
            "document_evidence.v1,0", "document_evidence.v1,1", "document_evidence.v1,3", "document_evidence.v1,4",
            "zero_break.v1,0", "zero_break.v1,1", "zero_break.v1,3", "zero_break.v1,4"})
    void shippedSelfAskCountEnablesRealExpansionWithoutSettingItsCallCount(String planId, int count) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        int shippedCount = original.at("/plan/overrides/knobs").path("expand.selfAsk.count").asInt(-1);
        assertEquals(3, shippedCount);
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.at("/plan/overrides/knobs"))
                .put("expand.selfAsk.count", count);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.at("/plan/overrides/knobs"))
                .put("expand.selfAsk.count", shippedCount);
        assertEquals(original, restored, "only the authored self-ask count changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(count == shippedCount
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertEquals(count, ((Number) metadata.get("expand.selfAsk.count")).intValue());
        assertEquals(count > 0, hints.isEnableSelfAsk());
        for (String flag : List.of("selfask.enabled", "enableSelfAsk")) {
            if (count > 0) assertEquals("true", metadata.get(flag));
            else assertFalse(metadata.containsKey(flag));
        }
        if (count > 0) assertEquals("expand.selfAsk.count", metadata.get("selfask.planOverride.reason"));
        else assertFalse(metadata.containsKey("selfask.planOverride.reason"));
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", false);
        ReflectionTestUtils.setField(retriever, "branchQualityEnabled", false);
        ReflectionTestUtils.setField(retriever, "useLlmSeeds", false);
        ReflectionTestUtils.setField(retriever, "useLlmFollowups", false);
        ExecutorService executor = newSingleSearchWorker("tbl07-selfask-count");
        ReflectionTestUtils.setField(retriever, "searchExecutor", executor);
        try {
            // Real projection keeps enableSelfAsk=true for positive counts; expansion remains active.
            List<Content> out = retriever.retrieve(QueryUtils.buildQuery("alpha beta", metadata));
            assertTrue(out.isEmpty());
            assertEquals(count > 0 ? 3 : 0, provider.calls.get());
            assertEquals(count > 0, TraceStore.get("selfask.planOverride.enabled"));
            Object attemptsValue = TraceStore.get("selfask.requery.attempts");
            int expandedCalls = attemptsValue instanceof List<?> attempts ? attempts.size() : 0;
            assertEquals(count > 0 ? 2 : 0, expandedCalls);
            if (count == 0) {
                assertEquals("global-disabled-no-plan-override", TraceStore.get("selfask.disabled.reason"));
            } else {
                assertEquals("expand.selfAsk.count", TraceStore.get("selfask.planOverride.reason"));
            }
            System.out.printf("TBL07_SELFASK plan=%s count=%d enabled=%s providerCalls=%d expandedCalls=%d rawCountPresent=true%n",
                    planId, count, hints.isEnableSelfAsk(), provider.calls.get(), expandedCalls);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "owned search worker must terminate");
        }
    }

    private static GuardContext completeWildCreativeContext() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        return context;
    }

    @Test
    void cheapSearchModeStopsAfterFirstSearchInsteadOfSelfAskFanout() {
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(retriever, "searchExecutor", executor);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", true);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 99);
        ReflectionTestUtils.setField(retriever, "maxDepth", 2);
        TraceStore.put("search.mode.lightAuxBypass", true);

        try {
            List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                    "오늘 기준으로 qwen3 8b 로컬 모델에서 가벼운 검색 모드가 검색 반복 없이 답하는지 간단히 확인해줘",
                    Map.of(
                            "enableSelfAsk", "true",
                            "searchMode", "FORCE_LIGHT")));

            assertTrue(out.isEmpty());
            assertEquals(1, provider.calls.get(), "FORCE_LIGHT should not fan out into Self-Ask provider calls");
            assertEquals(Boolean.TRUE, TraceStore.get("selfask.cheapSearchMode.skipped"));
            assertEquals("cheap-search-mode", TraceStore.get("selfask.cheapSearchMode.skipReason"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void planOverrideReasonTraceUsesSafeLabel() {
        String rawReason = "expand.selfAsk.count ownerToken=raw-owner-secret";
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", false);

        retriever.retrieve(QueryUtils.buildQuery(
                "short query",
                Map.of(
                        "selfask.enabled", "true",
                        "selfask.planOverride.reason", rawReason,
                        "enableSelfAsk", "false")));

        Object reason = TraceStore.get("selfask.planOverride.reason");
        assertTrue(String.valueOf(reason).startsWith("hash:"));
        assertFalse(String.valueOf(reason).contains("ownerToken"));
        assertFalse(String.valueOf(reason).contains("raw-owner-secret"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawReason));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceRequeryAttemptStoresOnlyRedactedCounts() {
        String rawSeed = "raw sensitive requery text should not appear";
        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "RC",
                "relation_hypothesis",
                3,
                0.75d,
                0.50d,
                0.20d,
                0.25d,
                0.80d,
                7,
                0.80d,
                0.50d,
                4,
                0.20d,
                0.45d,
                BranchQualityProbe.BranchAction.REWRITE_RETRY,
                "contribution_low");

        SelfAskWebSearchRetriever.traceRequeryAttempt(
                "RC",
                rawSeed,
                1.7d,
                0.42d,
                900L,
                7,
                3,
                1,
                false,
                "zero-results",
                metric,
                true);

        Object attemptsObj = TraceStore.get("selfask.requery.attempts");
        assertTrue(attemptsObj instanceof List<?>);
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) attemptsObj;
        Map<String, Object> event = attempts.get(0);

        assertEquals("RC", event.get("lane"));
        assertEquals(7, event.get("requestedTopK"));
        assertEquals(3, event.get("returnedCount"));
        assertEquals(1, event.get("afterFilterCount"));
        assertEquals("zero-results", event.get("failureClass"));
        assertEquals(true, event.get("retry"));
        assertEquals("RC", event.get("branchId"));
        assertEquals("relation_hypothesis", event.get("intentAxis"));
        assertEquals(3, event.get("retrievedCount"));
        assertEquals(0.75d, event.get("duplicateRatio"));
        assertEquals(0.80d, event.get("riskPenalty"));
        assertEquals("REWRITE_RETRY", event.get("action"));
        assertTrue(event.containsKey("seedHash12"));
        assertFalse(String.valueOf(attemptsObj).contains(rawSeed));
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceRequeryAttemptRedactsBranchMetricReason() {
        String secretShapedReason = "retry branch api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "RC",
                "relation_hypothesis",
                1,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                1.0d,
                0,
                0.0d,
                0.0d,
                1,
                0.20d,
                0.70d,
                BranchQualityProbe.BranchAction.REWRITE_RETRY,
                secretShapedReason);

        SelfAskWebSearchRetriever.traceRequeryAttempt(
                "RC",
                "safe seed",
                1.0d,
                0.20d,
                900L,
                4,
                0,
                0,
                true,
                "zero-results",
                metric,
                true);

        List<Map<String, Object>> attempts = (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
        String renderedReason = String.valueOf(attempts.get(0).get("reason"));

        assertFalse(renderedReason.contains("sk-"), renderedReason);
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceRequerySummaryPromotesCanonicalThreeWayRequeryKeys() throws Exception {
        Class<?> laneSeedClass = Class.forName(
                "com.example.lms.service.rag.SelfAskWebSearchRetriever$LaneSeed");
        Constructor<?> laneSeed = laneSeedClass.getDeclaredConstructor(String.class, String.class, double.class);
        laneSeed.setAccessible(true);
        List<Object> seeds = List.of(
                laneSeed.newInstance("BQ", "background definition query", 1.0d),
                laneSeed.newInstance("ER", "entity alias query", 1.0d),
                laneSeed.newInstance("RC", "relation correction query", 1.0d));
        Method summary = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "traceRequerySummary",
                List.class,
                int.class,
                Class.forName("com.example.lms.service.rag.query.SelfAskRewriteRiskScorer$Score"));
        summary.setAccessible(true);

        summary.invoke(null, seeds, 3, null);

        assertEquals(Boolean.TRUE, TraceStore.get("selfask.3way.requery.required"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.3way.requery.confirmed"));
        Map<String, Object> payload = (Map<String, Object>) TraceStore.get("selfask.requery.summary");
        assertEquals(3, ((Number) payload.get("laneCoverage")).intValue());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("background definition query"));
    }

    @Test
    void selfAskContentPreservesLaneAndHashMetadata() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("md.put(\"retrieval_lane\", lane);"));
        assertTrue(source.contains("md.put(\"retrieval_lane_role\", laneRole(lane));"));
        assertTrue(source.contains("md.put(\"branchId\", lane);"));
        assertTrue(source.contains("md.put(\"intentAxis\", laneRole(lane));"));
        assertTrue(source.contains("md.put(\"retrieval_query_hash12\", SafeRedactor.hash12(retrievalQuery));"));
        assertTrue(source.contains("md.put(\"parent_query_hash12\", SafeRedactor.hash12(parentQuery));"));
        assertTrue(source.contains("case") || source.contains("\"BQ\""));
    }

    @Test
    void selfAskLogsDoNotUseRawThrowableMessages() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.trim().matches(".*,[\\s]*(e|ex|t|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
    }

    @Test
    void selfAskRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();
        assertEquals(0L, exactEmptyCatches,
                "self-ask retrieval fail-soft helpers need safe breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void llmKeywordFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("LLM keyword generation failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(source.contains("LLM follow-up generation failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("LLM keyword generation failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("LLM follow-up generation failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void riskEmergentReasonsUseTraceLabels() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("meta.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"resource.riskEmergentReason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", risk.emergentAdjustment().reason());"));
        assertFalse(source.contains("\"reason\", risk.emergentAdjustment().reason()"));
        assertFalse(source.contains(
                "SafeRedactor.safeMessage(risk.emergentAdjustment().reason(), 120)"));
        assertFalse(source.contains("event.put(\"reason\", SafeRedactor.safeMessage(branchMetric.reason(), 120));"));
        assertTrue(source.contains(
                "SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), \"unknown\")"));
        assertTrue(source.contains("meta.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"resource.riskEmergentReason\", safeEmergentReason);"));
        assertTrue(source.contains("TraceStore.put(\"ml.risk.emergent.reason\", safeEmergentReason);"));
        assertTrue(source.contains("\"reason\", safeEmergentReason"));
        assertTrue(source.contains(
                "event.put(\"reason\", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\"));"));
    }

    @Test
    void retrySkipReasonRejectsSameAndVisitedQueriesBeforeBudgetUse() throws Exception {
        SelfAskWebSearchRetriever retriever = new SelfAskWebSearchRetriever(null, null, null, null);
        HashSet<String> visited = new HashSet<>();
        visited.add("alreadyvisited");

        assertEquals("same_parent_query", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "parent query", visited));
        assertEquals("same_branch_query", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "branch query", visited));
        assertEquals("already_visited", ReflectionTestUtils.invokeMethod(
                retriever, "retrySkipReason", "parent query", "branch query", "already visited", visited));

        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));
        int skipIdx = source.indexOf("String skipReason = retrySkipReason");
        int budgetIdx = source.indexOf("if (!budget.tryConsume())", skipIdx);
        assertTrue(skipIdx > 0);
        assertTrue(budgetIdx > skipIdx);
        assertTrue(source.contains("submitSearchAttempt(retryQuery, retryTopK)"));
        assertTrue(source.contains("long retryWaitMs = zero100LaneTimeboxMs(lane, waitMs);"));
        assertTrue(source.contains("getWithHardTimeout(retryFuture, retryWaitMs, retryQuery)"));
        assertTrue(source.contains("qHash="));
    }

    @Test
    void zero100LaneBudgetAndTimeboxWiringIsSourceStable() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("java.util.Map<String, LaneBudget> laneBudgets = zero100LaneBudgets(maxApiCallsPerQuery);"));
        assertTrue(source.contains("List<Future<SearchAttempt>> futures = new ArrayList<>();"));
        assertTrue(source.contains("SearchAttemptMeta attemptMeta = i < futureMeta.size()"));
        assertTrue(source.contains("? futureMeta.get(i)"));
        assertTrue(source.contains("String kw = attemptMeta.query();"));
        assertTrue(source.contains("submitSearchAttempt(kw, topKForKw)"));
        assertTrue(source.contains("\"skipped:lane_budget_exhausted\""));
        assertTrue(source.contains("zero100LaneTimeboxMs(laneForKw, reqPerRequestTimeoutMs)"));
        assertTrue(source.contains("TraceStore.append(\"zero100.branch.budgetRollover.events\""));
        assertFalse(source.contains("String kw = currentKeywords.get(i);"));
        assertFalse(source.contains("cancel(false)"));
        assertFalse(source.contains("Thread.interrupted()"));
    }

    @Test
    void safeSearchAttemptPreservesRateLimitFailureClass() throws Exception {
        SelfAskWebSearchRetriever retriever = newRetriever(new ThrowingProvider("HTTP 429 rate limit"));

        Object attempt = invokeSafeSearchAttempt(retriever, "raw sensitive rate limit query", 3);

        assertEquals("rate-limit", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertTrue(((List<?>) recordValue(attempt, "results")).isEmpty());
        assertFalse(String.valueOf(TraceStore.get("zero100.rollover.events")).contains("raw sensitive"));

        TraceStore.put("zero100.enabled", true);
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover(
                "BQ",
                String.valueOf(recordValue(attempt, "failureClass")),
                1));
        assertTrue(String.valueOf(TraceStore.get("zero100.rollover.events")).contains("rate-limit"));
    }

    @Test
    void safeSearchAttemptNormalizesCancellationFailureClass() throws Exception {
        String rawQuery = "raw cancellation query ownerToken=fake-token";
        SelfAskWebSearchRetriever retriever = newRetriever(new CancellingProvider());

        Object attempt = invokeSafeSearchAttempt(retriever, rawQuery, 3);

        assertEquals("cancelled", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertTrue(((List<?>) recordValue(attempt, "results")).isEmpty());
        assertFalse(String.valueOf(recordValue(attempt, "failureClass")).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void safeSearchAttemptPreservesProviderDisabledAndMissingKeyFailures() throws Exception {
        Object disabled = invokeSafeSearchAttempt(
                newRetriever(new ThrowingProvider("provider disabled by configuration")),
                "raw disabled query",
                3);
        Object missingKey = invokeSafeSearchAttempt(
                newRetriever(new ThrowingProvider("missing api key")),
                "raw missing key query",
                3);

        assertEquals("provider-disabled", recordValue(disabled, "failureClass"));
        assertEquals("missing-key-or-unauthorized", recordValue(missingKey, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(disabled, "fallback"));
        assertEquals(Boolean.TRUE, recordValue(missingKey, "fallback"));

        TraceStore.put("zero100.enabled", true);
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover("BQ", "provider_disabled", 1));
        assertTrue(SelfAskWebSearchRetriever.traceZero100Rollover("ER", "missing_external_key", 1));
        String events = String.valueOf(TraceStore.get("zero100.rollover.events"));
        assertTrue(events.contains("provider-disabled"));
        assertTrue(events.contains("missing-key-or-unauthorized"));
        assertFalse(events.contains("raw disabled query"));
        assertFalse(events.contains("raw missing key query"));
    }

    @Test
    void zero100LaneBudgetsNeverExceedGlobalCapForSmallTotals() throws Exception {
        Map<String, Object> one = zero100Budgets(1);
        Map<String, Object> two = zero100Budgets(2);

        assertEquals(1, sumRemaining(one));
        assertEquals(2, sumRemaining(two));
        assertEquals(1, sumInitial(one));
        assertEquals(2, sumInitial(two));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100LaneBudgetRolloverDoesNotInflateTargetWhenSourceEmpty() throws Exception {
        Map<String, Object> budgets = zero100Budgets(1);
        String sourceLane = null;
        for (Map.Entry<String, Object> entry : budgets.entrySet()) {
            if (remaining(entry.getValue()) == 0) {
                sourceLane = entry.getKey();
                break;
            }
        }
        if (sourceLane == null) {
            throw new AssertionError("expected at least one zero-call lane");
        }
        String targetLane = null;
        for (String lane : budgets.keySet()) {
            if (!lane.equals(sourceLane)) {
                targetLane = lane;
                break;
            }
        }
        if (targetLane == null) {
            throw new AssertionError("expected target lane");
        }
        int targetBefore = remaining(budgets.get(targetLane));

        Method move = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "moveOneLaneBudget", Map.class, String.class, String.class, String.class, int.class);
        move.setAccessible(true);
        move.invoke(null, budgets, sourceLane, targetLane, "rate_limit_local", 1);

        assertEquals(0, remaining(budgets.get(sourceLane)));
        assertEquals(targetBefore, remaining(budgets.get(targetLane)));
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) TraceStore.get("zero100.branch.budgetRollover.events");
        Map<String, Object> event = events.get(0);
        assertEquals(sourceLane, event.get("from"));
        assertEquals(targetLane, event.get("to"));
        assertEquals("rate-limit", event.get("failureClass"));
        assertEquals(0, event.get("movedCalls"));
        assertEquals(1, event.get("remainingGlobalBudget"));
        assertEquals(0, event.get("fromRemaining"));
        assertEquals(targetBefore, event.get("toRemaining"));
    }

    @Test
    void zero100KeywordOrderingPrioritizesActiveLane() {
        List<String> ordered = SelfAskWebSearchRetriever.orderZero100Keywords(
                List.of("strict anchor", "relaxed anchor", "explore anchor"),
                Map.of(
                        "strictanchor", "BQ",
                        "relaxedanchor", "ER",
                        "exploreanchor", "RC"),
                "RC");

        assertEquals("explore anchor", ordered.get(0));
    }

    @Test
    void zero100BurstSeedIncludesSerpApiAndTavilySkipReasons() throws Exception {
        TraceStore.put("web.serpapi.skipped.reason", "quota_exhausted");
        TraceStore.put("web.tavily.skipped.reason", "missing_tavily_api_key");

        String seed = zero100BurstSeed("parent query");

        assertTrue(seed.contains("quota_exhausted"), seed);
        assertTrue(seed.contains("missing_tavily_api_key"), seed);
        assertFalse(seed.contains("raw sensitive query should not leak"), seed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100RolloverTraceIsBoundedAndRedacted() {
        TraceStore.put("zero100.enabled", true);

        SelfAskWebSearchRetriever.traceZero100Rollover("RC", "timeout", 2);

        Object eventsObj = TraceStore.get("zero100.rollover.events");
        assertTrue(eventsObj instanceof List<?>);
        List<Map<String, Object>> events = (List<Map<String, Object>>) eventsObj;
        Map<String, Object> event = events.get(0);

        assertEquals(Boolean.TRUE, event.get("applied"));
        assertEquals("RC", event.get("currentLane"));
        assertEquals("BQ", event.get("nextLane"));
        assertEquals("timeout", event.get("failureClass"));
        assertEquals(1, event.get("movedCalls"));
        assertEquals(2, event.get("remainingGlobalBudget"));
        assertFalse(String.valueOf(eventsObj).contains("raw sensitive requery text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100RolloverAppliesNextLaneOrderingWithoutGrowingGlobalBudget() {
        TraceStore.put("zero100.enabled", true);
        SelfAskWebSearchRetriever.Zero100RolloverState state =
                new SelfAskWebSearchRetriever.Zero100RolloverState("BQ");

        boolean applied = SelfAskWebSearchRetriever.traceZero100Rollover(state, "BQ", "rate-limit", 1, 2);
        List<String> ordered = SelfAskWebSearchRetriever.orderZero100Keywords(
                List.of("strict anchor", "relaxed anchor", "explore anchor"),
                Map.of(
                        "strictanchor", "BQ",
                        "relaxedanchor", "ER",
                        "exploreanchor", "RC"),
                state.preferredLane(),
                state.cooledLane());

        assertTrue(applied);
        assertEquals("ER", state.preferredLane());
        assertEquals("BQ", state.cooledLane());
        assertEquals(1, state.movedCalls());
        assertEquals("relaxed anchor", ordered.get(0));
        assertEquals("strict anchor", ordered.get(2));

        List<Map<String, Object>> events = (List<Map<String, Object>>) TraceStore.get("zero100.rollover.events");
        Map<String, Object> event = events.get(0);
        assertEquals(1, event.get("remainingGlobalBudget"));
        assertEquals(2, event.get("depth"));
        assertEquals(1, event.get("movedCalls"));
    }

    @Test
    void zero100QueryBurstTraceUsesHashesOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("zero100.queryBurst.seedHashes"));
        assertTrue(source.contains("SafeRedactor.hash12(norm)"));
        assertFalse(source.contains("traceString(\"zero100.mpIntent\""));
        assertFalse(source.contains("TraceStore.put(\"zero100.queryBurst.seeds\""));
    }

    @Test
    void hardTimeoutTraceRequestsInterruptAndKeepsQueryRedacted() throws Exception {
        String rawQuery = "raw timeout query with api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        SelfAskWebSearchRetriever retriever = newRetriever(new CountingProvider());
        TimeoutRecordingFuture future = new TimeoutRecordingFuture();

        Object attempt = invokeHardTimeout(retriever, future, 25L, rawQuery);

        assertEquals("timeout", recordValue(attempt, "failureClass"));
        assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
        assertEquals(1, future.cancelCalls.get());
        assertEquals(Boolean.TRUE, future.lastMayInterruptIfRunning);
        assertEquals("hard_timeout", TraceStore.get("selfask.timeout.stage"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelRequested"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelInterrupt"));
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelAccepted"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.timeout.callerInterrupted"));
        assertEquals(25L, ((Number) TraceStore.get("selfask.timeout.timeoutMs")).longValue());
        assertTrue(String.valueOf(TraceStore.get("selfask.timeout.queryHash12")).matches("[0-9a-f]{12}"));
        assertFalse(TraceStore.getAll().containsKey("selfask.timeout.cancelSuppressed"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
    }

    @Test
    void interruptedWaitRestoresCallerInterruptAndCancelsOwnedAttempt() throws Exception {
        SelfAskWebSearchRetriever retriever = newRetriever(new CountingProvider());
        InterruptedRecordingFuture future = new InterruptedRecordingFuture();

        try {
            Object attempt = invokeHardTimeout(retriever, future, 100L, "interrupt boundary query");

            assertEquals("interrupted", recordValue(attempt, "failureClass"));
            assertEquals(Boolean.TRUE, recordValue(attempt, "fallback"));
            assertEquals(1, future.cancelCalls.get());
            assertEquals(Boolean.TRUE, future.lastMayInterruptIfRunning);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("interrupted_wait", TraceStore.get("selfask.timeout.stage"));
            assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.callerInterrupted"));
            assertEquals(Boolean.TRUE, TraceStore.get("selfask.timeout.cancelInterrupt"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preInterruptedRequestReturnsBeforeAsyncExpansionAndRetainsInterrupt() {
        CountingProvider provider = new CountingProvider();
        RecordingNoRunExecutor executor = new RecordingNoRunExecutor();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        configureAsyncTimeoutRetrieval(retriever, executor, 2, 400);
        AtomicInteger tavilyCalls = new AtomicInteger();
        ReflectionTestUtils.setField(retriever, "tavily", (ContentRetriever) ignored -> {
            tavilyCalls.incrementAndGet();
            return List.of();
        });

        Thread.currentThread().interrupt();
        try {
            List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                    "alpha vs beta pre interrupted request with enough detail",
                    Map.of("enableSelfAsk", "true")));

            assertTrue(out.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, provider.calls.get());
            assertEquals(0, executor.executeCalls.get());
            assertEquals(0, tavilyCalls.get());
        } finally {
            Thread.interrupted();
            executor.shutdown();
        }
    }

    @Test
    void hardTimeoutInterruptsRunningAttemptAndRecoversWrappedSingleWorker() throws Exception {
        BlockingAfterFirstProvider provider = new BlockingAfterFirstProvider(null);
        ExecutorService raw = newSingleSearchWorker("selfask-timeout-capacity");
        ExecutorService wrapped = new CancelShieldExecutorService(
                new ContextAwareExecutorService(raw), "searchIoExecutor");
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        configureAsyncTimeoutRetrieval(retriever, wrapped, 1, 80);

        try {
            List<Content> out = retriever.retrieve(QueryUtils.buildQuery(
                    "alpha vs beta capacity recovery comparison with enough detail",
                    Map.of("enableSelfAsk", "true")));

            assertTrue(provider.asyncStarted.await(1, TimeUnit.SECONDS));
            assertTrue(out.isEmpty());
            assertTrue(provider.asyncInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(provider.asyncExited.await(1, TimeUnit.SECONDS));

            CountDownLatch sentinelRan = new CountDownLatch(1);
            wrapped.execute(sentinelRan::countDown);
            assertTrue(sentinelRan.await(1, TimeUnit.SECONDS));
            assertEquals(2, provider.calls.get());
        } finally {
            provider.releaseNormally.countDown();
            wrapped.shutdownNow();
            wrapped.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptedWaitCancelsQueuedSiblingAndStopsLaterExpansion() throws Exception {
        CountDownLatch waiterReturned = new CountDownLatch(1);
        BlockingAfterFirstProvider provider = new BlockingAfterFirstProvider(waiterReturned);
        ExecutorService raw = newSingleSearchWorker("selfask-request-interrupt");
        ExecutorService wrapped = new CancelShieldExecutorService(
                new ContextAwareExecutorService(raw), "searchIoExecutor");
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        configureAsyncTimeoutRetrieval(retriever, wrapped, 2, 400);
        AtomicInteger tavilyCalls = new AtomicInteger();
        ReflectionTestUtils.setField(retriever, "tavily", (ContentRetriever) ignored -> {
            tavilyCalls.incrementAndGet();
            return List.of();
        });

        AtomicReference<List<Content>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                result.set(retriever.retrieve(QueryUtils.buildQuery(
                        "alpha vs beta request interruption with queued sibling",
                        Map.of("enableSelfAsk", "true"))));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                waiterReturned.countDown();
            }
        }, "selfask-request-waiter");
        waiter.setDaemon(true);

        try {
            waiter.start();
            assertTrue(provider.asyncStarted.await(1, TimeUnit.SECONDS));
            waiter.interrupt();
            waiter.join(2_000L);

            assertFalse(waiter.isAlive());
            assertTrue(failure.get() == null, String.valueOf(failure.get()));
            assertTrue(interruptedAtReturn.get());
            assertTrue(provider.asyncInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(provider.asyncExited.await(1, TimeUnit.SECONDS));
            assertTrue(result.get() != null && result.get().isEmpty());
            assertEquals(0, tavilyCalls.get());
            assertEquals(2, provider.calls.get());

            CountDownLatch sentinelRan = new CountDownLatch(1);
            wrapped.execute(sentinelRan::countDown);
            assertTrue(sentinelRan.await(1, TimeUnit.SECONDS));
        } finally {
            waiterReturned.countDown();
            provider.releaseNormally.countDown();
            waiter.interrupt();
            waiter.join(1_000L);
            wrapped.shutdownNow();
            wrapped.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void zero100BudgetReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertFalse(source.contains("TraceStore.put(\"zero100.queryBurst.budget.reason\", decision.reason());"));
        assertFalse(source.contains(
                "TraceStore.put(\"zero100.queryBurst.budget.reason\", SafeRedactor.safeMessage(decision.reason(), 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"zero100.queryBurst.budget.reason\", SafeRedactor.traceLabelOrFallback(decision.reason(), \"unknown\"));"));
    }

    @Test
    void zero100BudgetGovernorUsesFreshnessQueryNotTextureOnlineFlag() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java"));

        assertTrue(source.contains("retrievalBudgetGovernor.decide(anchorResult, textureLookup.hitRate(), freshnessQuery,"));
        assertFalse(source.contains("retrievalBudgetGovernor.decide(anchorResult, textureLookup.hitRate(), textureLookup.onlineSearchNeeded()"));
    }

    @Test
    void logicDagDisabledKeepsBranchSeedOrder() throws Exception {
        SelfAskWebSearchRetriever retriever = newRetriever(new CountingProvider());
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", false);

        List<?> seeds = invokeBranch3Seeds(retriever, "who is Ada and what aliases are related");

        assertEquals(List.of("BQ", "ER", "RC"), laneSeedLanes(seeds));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.logicDag.enabled"));
        assertEquals("disabled", TraceStore.get("selfask.logicDag.failureClass"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledLogicDagOrdersSearchAttemptsByDagWithoutRawQueryTrace() {
        String rawQuery = "who is Ada Lovelace and which aliases are related in computing history";
        RecordingProvider provider = new RecordingProvider(rawQuery);
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", true);
        ReflectionTestUtils.setField(retriever, "searchExecutor", executor);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", true);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 99);

        try {
            retriever.retrieve(QueryUtils.buildQuery(
                    rawQuery,
                    Map.of("enableSelfAsk", "true")));
        } finally {
            executor.shutdownNow();
        }

        List<Map<String, Object>> attempts =
                (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
        assertEquals(List.of("ER", "BQ", "RC"), attempts.stream()
                .map(row -> String.valueOf(row.get("lane")))
                .limit(3)
                .toList());
        assertEquals("entity_first", TraceStore.get("selfask.logicDag.dependencyMode"));
        assertEquals(List.of("ER", "BQ", "RC"), TraceStore.get("selfask.logicDag.topologicalOrder"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertTrue(provider.calls.get() >= 4);
    }

    @Test
    void logicDagPlannerFailurePreservesBranchSeedOrder() throws Exception {
        SelfAskWebSearchRetriever retriever = new FailingLogicDagRetriever(new CountingProvider());
        enableThreeWayPlanner(retriever);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", true);

        List<?> seeds = invokeBranch3Seeds(retriever, "who is Ada and what aliases are related");

        assertEquals(List.of("BQ", "ER", "RC"), laneSeedLanes(seeds));
        assertEquals("cycle_detected", TraceStore.get("selfask.logicDag.failureClass"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "strict_authored,1,1,1", "strict_zero,0,1,1", "strict_high,1,0,0",
            "strict_negative,1,1,1", "strict_removed,1,1,1", "strict_request,0,1,1",
            "relaxed_authored,1,1,1", "relaxed_zero,1,0,1", "relaxed_high,0,1,0",
            "relaxed_negative,1,1,1", "relaxed_removed,1,1,1", "relaxed_request,1,0,1",
            "explore_authored,1,1,1", "explore_zero,1,1,0", "explore_high,0,0,1",
            "explore_negative,1,1,1", "explore_removed,1,1,1", "explore_request,1,1,0",
            "all_zero,1,1,1", "engine_off,1,1,1", "threeway_off,0,0,0",
            "cap_0,0,0,0", "cap_1,1,0,0", "cap_2,1,1,0"})
    @SuppressWarnings("unchecked")
    void shippedZero100CallRatiosControlActualThreeLaneAdmission(
            String control, int bqCalls, int erCalls, int rcCalls) throws Throwable {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        List<String> branches = List.of("strict", "relaxed", "explore");
        List<String> lanes = List.of("BQ", "ER", "RC");
        String branch = control.substring(0, control.indexOf('_'));
        String variant = control.substring(control.indexOf('_') + 1);
        String key = "search.zero100." + branch + "CallBudgetRatio";
        if (branches.contains(branch)) {
            assertEquals(branch.equals("strict") ? 0.34d : 0.33d, params.path(key).asDouble());
            switch (variant) {
                case "zero" -> params.put(key, 0.0d);
                case "high", "request" -> params.put(key, 9.0d);
                case "negative" -> params.put(key, -1.0d);
                case "removed" -> params.remove(key);
                default -> { }
            }
            var restored = modified.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                    .set(key, original.path("params").path(key));
            assertEquals(original, restored, "only one raw plan ratio may change");
        } else if (control.equals("all_zero")) {
            for (String item : branches) params.put("search.zero100." + item + "CallBudgetRatio", 0.0d);
        } else {
            assertEquals(original, modified);
        }
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load("zero100.v1");
        assertEquals("zero100.v1", plan.planId());
        GuardContext ctx = new GuardContext();
        if (variant.equals("request")) ctx.putPlanOverride(key, 0.0d);
        applier.applyToGuardContext(plan, ctx);
        for (String item : branches) {
            String itemKey = "search.zero100." + item + "CallBudgetRatio";
            if (item.equals(branch) && variant.equals("request")) {
                assertEquals(0.0d, ctx.planDouble(itemKey, -99.0d));
            } else if (params.has(itemKey)) {
                assertEquals(params.path(itemKey).asDouble(), ctx.planDouble(itemKey, -99.0d));
            } else {
                assertEquals(null, ctx.getPlanOverride(itemKey));
            }
        }
        GuardContextHolder.set(ctx);
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        props.setEngineEnabled(!control.equals("engine_off"));
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(props,
                new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props),
                new org.springframework.mock.env.MockEnvironment());
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        int cap = branch.equals("cap") ? Integer.parseInt(variant) : 3;
        ExecutorService executor = newSingleSearchWorker("tbl07-zero100-ratios");
        configureAsyncTimeoutRetrieval(retriever, executor, cap, 500);
        var planner = org.mockito.Mockito.mock(SelfAskPlanner.class);
        List<String> seeds = List.of("strict fixture", "relaxed fixture", "explore fixture");
        org.mockito.Mockito.when(planner.generateThreeLanes(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, seeds.get(0), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.ER, seeds.get(1), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, seeds.get(2), Map.of())));
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", !control.equals("threeway_off"));
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", planner);
        String question = "alpha beta gamma delta epsilon";
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{question});
        org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
            if (props.isEngineEnabled()) {
                assertEquals("BQ", TraceStore.get("zero100.activeLane"));
                assertEquals("CALIBRATE", TraceStore.get("zero100.phase"));
                assertEquals(0, TraceStore.get("zero100.progressPct"));
                assertEquals(Map.of("BQ", 950L, "ER", 800L, "RC", 750L),
                        TraceStore.get("zero100.branch.timeboxMs"), "call ratios must not change timeboxes");
                var ratios = (Map<String, Double>) TraceStore.get("zero100.branch.callRatios");
                assertEquals(1.0d, ratios.values().stream().mapToDouble(Double::doubleValue).sum(), 0.000151d,
                        "three independently rounded four-decimal ratios may not sum to exactly one");
                if (branches.contains(branch) && (variant.equals("zero") || variant.equals("request"))) {
                    double ratio = ratios.get(lanes.get(branches.indexOf(branch)));
                    assertTrue(ratio > 0.0d && ratio < 0.001d,
                            "time-budget adjustment retains a tiny positive floor before integer allocation");
                }
                if (variant.equals("authored") || variant.equals("negative")
                        || variant.equals("removed") || control.equals("all_zero")) {
                    assertEquals(0.34d, ratios.get("BQ"), 1.0e-9d);
                    assertEquals(0.33d, ratios.get("ER"), 1.0e-9d);
                    assertEquals(0.33d, ratios.get("RC"), 1.0e-9d);
                }
            } else {
                assertFalse(TraceStore.getAll().containsKey("zero100.branch.callRatios"));
            }
            return retriever.retrieve(new Query(question));
        });
        try {
            assertEquals(List.of(), aspect.aroundChatEntry(pjp));
            int[] expected = {bqCalls, erCalls, rcCalls};
            var attempts = (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
            var budgets = (List<Map<String, Object>>) TraceStore.get("zero100.branch.callBudget.events");
            int skips = 0;
            for (int i = 0; i < lanes.size(); i++) {
                String lane = lanes.get(i);
                assertEquals(expected[i], java.util.Collections.frequency(provider.queries, seeds.get(i)),
                        "actual provider admission for " + control + ":" + lane);
                if (props.isEngineEnabled() && !control.equals("threeway_off")) {
                    assertTrue(budgets.stream().anyMatch(row -> lane.equals(row.get("lane"))));
                    long skipped = attempts.stream().filter(row -> lane.equals(row.get("lane")))
                            .filter(row -> "skipped:lane_budget_exhausted".equals(row.get("failureClass"))).count();
                    assertEquals(expected[i] == 0 ? 1L : 0L, skipped);
                    skips += (int) skipped;
                }
            }
            assertEquals(1, java.util.Collections.frequency(provider.queries, question), "one direct attempt");
            assertEquals(1 + cap, provider.calls.get(), "direct attempt plus bounded expansion attempts");
            assertEquals(control.equals("threeway_off") ? 0 : 3, TraceStore.get("selfask.branch3.seedCount"));
            System.out.printf("TBL07_ZERO100 control=%s cap=%d BQ=%d ER=%d RC=%d skips=%d providerCalls=%d%n",
                    control, cap, bqCalls, erCalls, rcCalls, skips, provider.calls.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "strict,authored,950,950,false", "strict,zero,250,250,true",
            "strict,high,2339,2339,false", "strict,negative,950,950,false",
            "strict,removed,950,950,false", "strict,request,250,250,true",
            "relaxed,authored,800,800,false", "relaxed,zero,250,250,true",
            "relaxed,high,2325,2325,false", "relaxed,negative,800,800,false",
            "relaxed,removed,800,800,false", "relaxed,request,250,250,true",
            "explore,authored,750,750,false", "explore,zero,250,250,true",
            "explore,high,2320,2320,false", "explore,negative,750,750,false",
            "explore,removed,750,750,false", "explore,request,250,250,true",
            "strict,all_zero,950,950,false", "strict,request_cap,950,100,true",
            "strict,level_cap,2339,1000,true",
            "strict,web_authored,950,950,false", "strict,web_short,250,250,true"})
    @SuppressWarnings("unchecked")
    void shippedZero100TimeboxRatiosChangeActualWaitForTheSameHeldProvider(
            String branch, String variant, long expectedLaneMs, long expectedWaitMs, boolean timedOut)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        List<String> branches = List.of("strict", "relaxed", "explore");
        List<String> lanes = List.of("BQ", "ER", "RC");
        List<String> seeds = List.of("strict fixture", "relaxed fixture", "explore fixture");
        int selected = branches.indexOf(branch);
        String lane = lanes.get(selected);
        boolean webControl = variant.startsWith("web_");
        String key = "search.zero100." + (webControl ? "webTimeboxMs" : branch + "TimeboxRatio");
        if (webControl) assertEquals(2500, params.path(key).asInt());
        switch (variant) {
            case "zero" -> params.put(key, 0.0d);
            case "web_short" -> params.put(key, 500);
            case "high", "request", "level_cap" -> params.put(key, 9.0d);
            case "negative" -> params.put(key, -1.0d);
            case "removed" -> params.remove(key);
            case "all_zero" -> branches.forEach(b -> params.put("search.zero100." + b + "TimeboxRatio", 0.0d));
            default -> { }
        }
        var restored = modified.deepCopy();
        var restoredParams = (com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params");
        for (String item : variant.equals("all_zero") ? branches : List.of(branch)) {
            String itemKey = webControl ? key : "search.zero100." + item + "TimeboxRatio";
            restoredParams.set(itemKey, original.path("params").path(itemKey));
        }
        assertEquals(original, restored, "only declared timebox fields change");
        if (!webControl) assertEquals(2500, params.path("search.zero100.webTimeboxMs").asInt());
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load("zero100.v1");
        assertEquals("zero100.v1", plan.planId());
        GuardContext ctx = new GuardContext();
        if (variant.equals("request")) ctx.putPlanOverride(key, 0.0d);
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(props,
                new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props),
                new org.springframework.mock.env.MockEnvironment());
        SelectedLaneBlockingProvider provider = new SelectedLaneBlockingProvider(seeds.get(selected));
        ExecutorService searchRaw = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "tbl07-zero100-timebox-search");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService search = new CancelShieldExecutorService(
                new ContextAwareExecutorService(searchRaw), "searchIoExecutor");
        ExecutorService caller = newSingleSearchWorker("tbl07-zero100-timebox-caller");
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        configureAsyncTimeoutRetrieval(retriever, search, 3, variant.equals("request_cap") ? 100 : 3000);
        ReflectionTestUtils.setField(retriever, "selfAskTimeoutSec", variant.equals("level_cap") ? 1 : 10);
        var planner = org.mockito.Mockito.mock(SelfAskPlanner.class);
        org.mockito.Mockito.when(planner.generateThreeLanes(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, seeds.get(0), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.ER, seeds.get(1), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, seeds.get(2), Map.of())));
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", planner);
        String question = "alpha beta gamma delta epsilon";
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong elapsedMs = new java.util.concurrent.atomic.AtomicLong();
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{question});
        try {
            org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
                assertEquals("BQ", TraceStore.get("zero100.activeLane"));
                assertEquals("CALIBRATE", TraceStore.get("zero100.phase"));
                assertEquals(0, TraceStore.get("zero100.progressPct"));
                assertEquals(Map.of("BQ", 0.34d, "ER", 0.33d, "RC", 0.33d),
                        TraceStore.get("zero100.branch.callRatios"), "timebox ratios do not change admission ratios");
                assertEquals(expectedLaneMs,
                        ((Map<String, Long>) TraceStore.get("zero100.branch.timeboxMs")).get(lane));
                long started = System.nanoTime();
                List<Content> result = retriever.retrieve(new Query(question));
                elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return result;
            });
            Future<List<Content>> request = caller.submit(() -> {
                TraceStore.clear();
                GuardContextHolder.set(ctx);
                try {
                    applier.applyToGuardContext(plan, ctx);
                    if (variant.equals("request")) assertEquals(0.0d, ctx.planDouble(key, -99.0d));
                    else if (params.has(key)) assertEquals(params.path(key).asDouble(), ctx.planDouble(key, -99.0d));
                    else assertEquals(null, ctx.getPlanOverride(key));
                    return (List<Content>) aspect.aroundChatEntry(pjp);
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                } finally {
                    trace.set(new java.util.HashMap<>(TraceStore.getAll()));
                    GuardContextHolder.clear();
                    TraceStore.clear();
                }
            });
            assertTrue(provider.entered.await(2, TimeUnit.SECONDS), "selected real provider attempt started");
            List<Content> result;
            if (timedOut) {
                result = request.get(2, TimeUnit.SECONDS);
                assertTrue(result.isEmpty());
                assertTrue(provider.interrupted.await(1, TimeUnit.SECONDS), "owned future requested interruption");
                assertEquals(1L, provider.exited.getCount(), "caller returned while fake provider remains in flight");
                assertTrue(elapsedMs.get() >= expectedWaitMs - 70L,
                        "caller must actually wait for its effective deadline");
                assertEquals("hard_timeout", trace.get().get("selfask.timeout.stage"),
                        "drained completed siblings must not overwrite the blocking attempt timeout");
                assertEquals(Boolean.TRUE, trace.get().get("selfask.timeout.cancelAccepted"));
            } else {
                org.junit.jupiter.api.Assertions.assertThrows(TimeoutException.class,
                        () -> request.get(400, TimeUnit.MILLISECONDS), "same held provider must keep caller waiting");
                assertEquals(1L, provider.interrupted.getCount());
                assertEquals(1L, provider.exited.getCount());
                provider.release.countDown();
                result = request.get(2, TimeUnit.SECONDS);
                assertEquals(1, result.size());
                assertTrue(result.get(0).textSegment().text().contains("Timed fixture evidence"));
                assertFalse(trace.get().containsKey("selfask.timeout.stage"));
                assertTrue(elapsedMs.get() >= 350L);
            }
            var events = (List<Map<String, Object>>) trace.get().get("selfask.requery.attempts");
            var event = events.stream().filter(e -> lane.equals(e.get("lane"))).findFirst().orElseThrow();
            long effectiveWait = ((Number) event.get("timeoutMs")).longValue();
            assertTrue(effectiveWait <= expectedWaitMs && effectiveWait >= expectedWaitMs - 30L);
            assertEquals(timedOut ? "timeout" : "none", event.get("failureClass"));
            assertEquals(4, provider.calls.get(), "direct search plus three admitted lane calls");
            for (String seed : seeds) assertEquals(1, java.util.Collections.frequency(provider.queries, seed));
            System.out.printf("TBL07_TIMEBOX branch=%s control=%s key=%s laneMs=%d waitMs=%d elapsedMs=%d timedOut=%s providerCalls=%d providerInFlightAtReturn=%s%n",
                    branch, variant, webControl ? "webTimeboxMs" : branch + "TimeboxRatio", expectedLaneMs, effectiveWait, elapsedMs.get(), timedOut,
                    provider.calls.get(), timedOut);
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        } finally {
            provider.release.countDown();
            caller.shutdownNow();
            search.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(searchRaw.awaitTermination(3, TimeUnit.SECONDS));
            if (provider.entered.getCount() == 0) assertEquals(0L, provider.exited.getCount());
        }
    }

    private static final class SelectedLaneBlockingProvider implements WebSearchProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        private final String selectedQuery;

        private SelectedLaneBlockingProvider(String selectedQuery) { this.selectedQuery = selectedQuery; }

        @Override public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            queries.add(query);
            if (!query.equals(selectedQuery)) return List.of();
            entered.countDown();
            boolean restoreInterrupt = false;
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        restoreInterrupt = true;
                        interrupted.countDown();
                    }
                }
                return List.of("Timed fixture evidence https://fixture.example/timebox");
            } finally {
                exited.countDown();
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }

        @Override public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }
        @Override public boolean isEnabled() { return true; }
        @Override public String getName() { return "selected-lane-blocking"; }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "done_success,0,none", "done_success,-1,none", "done_success,25,none",
            "done_empty,0,zero-results", "done_error,0,provider-disabled",
            "done_cancelled,0,cancelled", "pending,0,timeout", "pending,-1,timeout",
            "missing,0,missing_future"})
    @SuppressWarnings("unchecked")
    void completedSelfAskAttemptsDrainWithoutAnotherBudgetedWait(
            String control, long timeoutMs, String expectedFailure) throws Exception {
        WebSearchProvider provider = org.mockito.Mockito.mock(WebSearchProvider.class);
        org.mockito.Mockito.when(provider.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(provider.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of("completed fixture evidence"));
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        Object completed = invokeSafeSearchAttempt(retriever, "fixture", 1);
        Future<Object> future = org.mockito.Mockito.mock(Future.class);
        org.mockito.Mockito.when(future.isDone()).thenReturn(control.startsWith("done_"));
        org.mockito.Mockito.when(future.cancel(true)).thenReturn(true);
        switch (control) {
            case "done_success" -> org.mockito.Mockito.when(future.get()).thenReturn(completed);
            case "done_error" -> org.mockito.Mockito.when(future.get()).thenThrow(
                    new ExecutionException(new IllegalStateException("provider disabled by configuration")));
            case "done_cancelled" -> org.mockito.Mockito.when(future.get()).thenThrow(new CancellationException());
            default -> { }
        }
        Object attempt = invokeHardTimeout(retriever, control.equals("missing") ? null : future,
                timeoutMs, "fixture");
        assertEquals(expectedFailure, recordValue(attempt, "failureClass"));
        assertEquals(control.equals("done_success") ? List.of("completed fixture evidence") : List.of(),
                recordValue(attempt, "results"));
        org.mockito.Mockito.verify(future, org.mockito.Mockito.never()).get(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        if (control.startsWith("done_")) {
            org.mockito.Mockito.verify(future).get();
            org.mockito.Mockito.verify(future, org.mockito.Mockito.never()).cancel(true);
            assertFalse(TraceStore.getAll().containsKey("selfask.timeout.stage"));
        } else if (control.equals("pending")) {
            org.mockito.Mockito.verify(future, org.mockito.Mockito.never()).get();
            org.mockito.Mockito.verify(future).cancel(true);
            assertEquals("deadline_exhausted", TraceStore.get("selfask.timeout.stage"));
        }
        System.out.printf("TBL07_DRAIN control=%s timeoutMs=%d failureClass=%s timedGetCalls=0%n",
                control, timeoutMs, expectedFailure);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void selfAskLevelDeadlinePreservesCompletedSiblingWithoutWaitingForLateSibling(boolean late)
            throws Exception {
        SelectedLaneBlockingProvider first = new SelectedLaneBlockingProvider("alpha");
        CountDownLatch siblingEntered = new CountDownLatch(1);
        CountDownLatch siblingRelease = new CountDownLatch(1);
        CountDownLatch siblingBodyExited = new CountDownLatch(1);
        CountDownLatch siblingFutureCompleted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Future<?>> siblingFuture = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong firstStarted = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong siblingCompleted = new java.util.concurrent.atomic.AtomicLong();
        WebSearchProvider provider = org.mockito.Mockito.mock(WebSearchProvider.class);
        org.mockito.Mockito.when(provider.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(provider.getName()).thenReturn("completed-sibling-fixture");
        org.mockito.Mockito.when(provider.search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            String query = invocation.getArgument(0);
            if (query.equals("alpha")) {
                firstStarted.set(System.nanoTime());
                return first.search(query, invocation.getArgument(1));
            }
            if (!query.equals("beta")) return List.of();
            siblingEntered.countDown();
            boolean restoreInterrupt = false;
            try {
                if (late) {
                    while (true) {
                        try { siblingRelease.await(); break; }
                        catch (InterruptedException ignored) { restoreInterrupt = true; }
                    }
                }
                return List.of("Completed sibling evidence https://fixture.example/completed");
            } finally {
                siblingBodyExited.countDown();
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        });
        ExecutorService raw = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "selfask-completed-sibling-search");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService observed = org.mockito.Mockito.mock(ExecutorService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Runnable command = invocation.getArgument(0);
            int ordinal = submissions.incrementAndGet();
            if (ordinal == 2) siblingFuture.set((Future<?>) command);
            raw.execute(() -> {
                command.run();
                if (ordinal == 2) {
                    siblingCompleted.set(System.nanoTime());
                    siblingFutureCompleted.countDown();
                }
            });
            return null;
        }).when(observed).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        ExecutorService caller = newSingleSearchWorker("selfask-completed-sibling-caller");
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        configureAsyncTimeoutRetrieval(retriever, observed, 2, 2000);
        AtomicReference<Map<String, Object>> trace = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong elapsedMs = new java.util.concurrent.atomic.AtomicLong();
        try {
            Future<List<Content>> request = caller.submit(() -> {
                TraceStore.clear();
                GuardContextHolder.clear();
                long started = System.nanoTime();
                try { return retriever.retrieve(new Query("alpha beta gamma delta epsilon")); }
                finally {
                    elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    trace.set(new java.util.HashMap<>(TraceStore.getAll()));
                    TraceStore.clear();
                    GuardContextHolder.clear();
                }
            });
            assertTrue(first.entered.await(2, TimeUnit.SECONDS));
            assertTrue(siblingEntered.await(400, TimeUnit.MILLISECONDS));
            if (!late) {
                assertTrue(siblingFutureCompleted.await(400, TimeUnit.MILLISECONDS));
                assertTrue(siblingFuture.get().isDone(), "independent actual Future completion before deadline");
                assertFalse(siblingFuture.get().isCancelled());
                assertTrue(siblingCompleted.get() - firstStarted.get() < TimeUnit.MILLISECONDS.toNanos(500));
            }
            List<Content> result = request.get(2, TimeUnit.SECONDS);
            assertTrue(first.interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(1L, first.exited.getCount(), "first provider remains held after caller returns");
            assertEquals(3, calls.get());
            assertEquals(2, submissions.get(), "no extra admission after the deadline");
            assertTrue(elapsedMs.get() >= 900 && elapsedMs.get() < 1800,
                    "one level deadline without another full wait");
            if (late) {
                assertTrue(result.isEmpty());
                assertTrue(siblingFuture.get().isCancelled());
                assertEquals(1L, siblingBodyExited.getCount(), "late backing provider still held");
            } else {
                assertEquals(1, result.size(), "predeadline completed sibling evidence must survive first-lane timeout");
                assertTrue(result.get(0).textSegment().text().contains("Completed sibling evidence"));
            }
            System.out.printf("TBL07_SIBLING late=%s resultCount=%d providerCalls=%d submissions=%d elapsedMs=%d firstProviderInFlight=true completedBeforeDeadline=%s%n",
                    late, result.size(), calls.get(), submissions.get(), elapsedMs.get(), !late);
        } finally {
            first.release.countDown();
            siblingRelease.countDown();
            caller.shutdownNow();
            raw.shutdownNow();
            assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(raw.awaitTermination(3, TimeUnit.SECONDS));
            if (first.entered.getCount() == 0) assertEquals(0L, first.exited.getCount());
            if (siblingEntered.getCount() == 0) assertEquals(0L, siblingBodyExited.getCount());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "strict,authored,1.0", "strict,zero,0.05", "strict,high,1.25",
            "strict,negative,0.05", "strict,nonfinite,1.0", "strict,removed,1.0",
            "strict,request,0.05", "strict,cap,1.25",
            "relaxed,authored,1.0", "relaxed,zero,0.05", "relaxed,high,1.25",
            "relaxed,negative,0.05", "relaxed,nonfinite,1.0", "relaxed,removed,1.0",
            "relaxed,request,0.05", "relaxed,cap,1.25",
            "explore,authored,1.0", "explore,zero,0.05", "explore,high,1.25",
            "explore,negative,0.05", "explore,nonfinite,1.0", "explore,removed,1.0",
            "explore,request,0.05", "explore,cap,1.25",
            "strict,phase_diverge,1.15", "strict,phase_cross,1.05",
            "strict,slice_authored,1.15", "explore,slice_alternate,1.15"})
    @SuppressWarnings("unchecked")
    void shippedZero100WeightsChangeActualProviderTopK(
            String branch, String control, double expectedWeight) throws Throwable {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        List<String> branches = List.of("strict", "relaxed", "explore");
        List<String> lanes = List.of("BQ", "ER", "RC");
        List<String> seeds = List.of("strict fixture", "relaxed fixture", "explore fixture");
        int selected = branches.indexOf(branch);
        boolean phaseControl = control.startsWith("phase_");
        boolean sliceControl = control.startsWith("slice_");
        boolean progressedControl = phaseControl || sliceControl;
        boolean crossPhase = control.equals("phase_cross");
        double neutralWeight = crossPhase ? 0.95d : 1.0d;
        String key = "search.zero100." + (sliceControl ? "sliceMs" : phaseControl ? "crossVerifyStartPct" : branch + "Weight");
        assertEquals(sliceControl ? 400.0d : phaseControl ? 55.0d : 1.0d, params.path(key).asDouble());
        switch (control) {
            case "zero" -> params.put(key, 0.0d);
            case "phase_cross" -> params.put(key, 15);
            case "slice_alternate" -> params.put(key, 600);
            case "high", "request", "cap" -> params.put(key, 9.0d);
            case "negative" -> params.put(key, -1.0d);
            case "nonfinite" -> params.put(key, "NaN");
            case "removed" -> params.remove(key);
            default -> { }
        }
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the selected raw weight, phase threshold or slice duration may change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        if (control.equals("request")) ctx.putPlanOverride(key, 0.0d);
        if (progressedControl) ctx.putPlanOverride("search.zero100.queryBurstMax", 0);
        applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
        if (control.equals("removed")) assertEquals(null, ctx.getPlanOverride(key));
        else if (control.equals("nonfinite")) assertEquals("NaN", String.valueOf(ctx.getPlanOverride(key)));
        else assertEquals(control.equals("request") ? 0.0d : params.path(key).asDouble(), ctx.planDouble(key, -99.0d));
        GuardContextHolder.set(ctx);
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        props.setEngineEnabled(true);
        var registry = new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props);
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(props, registry,
                new org.springframework.mock.env.MockEnvironment());
        var actualTopK = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        AtomicInteger calls = new AtomicInteger();
        WebSearchProvider provider = new WebSearchProvider() {
            @Override public List<String> search(String query, int topK) {
                calls.incrementAndGet();
                assertEquals(null, actualTopK.putIfAbsent(query, topK), "one actual call per distinct query");
                return List.of();
            }
            @Override public SearchResult searchWithTrace(String query, int topK) {
                return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
            }
            @Override public boolean isEnabled() { return true; }
            @Override public String getName() { return "weight-fixture"; }
        };
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ExecutorService executor = newSingleSearchWorker("tbl07-zero100-weights");
        configureAsyncTimeoutRetrieval(retriever, executor, 3, 500);
        ReflectionTestUtils.setField(retriever, "laneTopK", 16);
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        var riskFactors = new java.util.concurrent.atomic.AtomicReference<Map<String, Double>>();
        var planner = org.mockito.Mockito.mock(SelfAskPlanner.class);
        org.mockito.Mockito.when(planner.generateThreeLanes(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
            riskFactors.set(Map.copyOf((Map<String, Double>) invocation.getArgument(3)));
            return List.of(
                    new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, seeds.get(0), Map.of()),
                    new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.ER, seeds.get(1), Map.of()),
                    new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, seeds.get(2), Map.of()));
        });
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", planner);
        int requestK = control.equals("cap") ? 1 : 64;
        String question = "alpha beta gamma delta epsilon";
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{question});
        org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
            assertEquals(sliceControl ? lanes.get(selected) : "BQ", TraceStore.get("zero100.activeLane"));
            assertEquals(progressedControl ? (crossPhase ? "CROSS_VERIFY" : "DIVERGE") : "CALIBRATE",
                    TraceStore.get("zero100.phase"));
            if (progressedControl) {
                assertEquals(sliceControl ? 20 : 30, TraceStore.get("zero100.progressPct"));
                assertEquals(0, TraceStore.get("zero100.queryBurst.max"));
                long expectedIndex = sliceControl ? (control.equals("slice_alternate") ? 2000L : 3000L) : 4500L;
                assertEquals(expectedIndex, TraceStore.get("zero100.slice.idx"), "actual slice must not drift into another branch");
            } else {
                assertEquals(Map.of("BQ", 0.34d, "ER", 0.33d, "RC", 0.33d), TraceStore.get("zero100.branch.callRatios"));
                assertEquals(Map.of("BQ", 950L, "ER", 800L, "RC", 750L), TraceStore.get("zero100.branch.timeboxMs"));
            }
            var weights = (Map<String, Double>) TraceStore.get("zero100.branch.weights");
            for (int i = 0; i < lanes.size(); i++) assertEquals(i == selected ? expectedWeight : neutralWeight, weights.get(lanes.get(i)));
            return retriever.retrieve(QueryUtils.buildQuery(question, Map.of("webTopK", requestK)));
        });
        Object phaseState = null;
        long phaseBudget = 0L;
        if (progressedControl) {
            var first = registry.touch(com.example.lms.trace.LogCorrelation.sessionId(), question,
                    ctx.planLong("search.zero100.maxMinutes", props.getMaxMinutes()),
                    ctx.planLong("search.zero100.sliceMs", props.getSliceMs()),
                    ctx.planLong("search.zero100.webTimeboxMs", props.getWebCallTimeboxMs()),
                    ctx.planLong("search.zero100.backoffHardCapMs", props.getBackoffHardCapMs()));
            assertEquals("CALIBRATE", ai.abandonware.nova.orch.zero100.Zero100BranchScheduler.schedule(first, ctx).phase().name());
            var sessions = (Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions");
            assertEquals(1, sessions.size());
            phaseState = sessions.values().iterator().next();
            phaseBudget = ((Number) ReflectionTestUtils.getField(phaseState, "budgetMs")).longValue();
            assertEquals(6_000_000L, phaseBudget);
        }
        try {
            // Own test session only; real touch recomputes the deadline and slice.
            if (progressedControl) ReflectionTestUtils.setField(phaseState, "createdAtMs",
                    System.currentTimeMillis() - phaseBudget * (sliceControl ? 20 : 30) / 100 - 100L);
            assertEquals(List.of(), aspect.aroundChatEntry(pjp));
            if (progressedControl) {
                org.junit.jupiter.api.Assertions.assertSame(phaseState,
                        ((Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions")).values().iterator().next());
                assertEquals(((Number) ReflectionTestUtils.getField(phaseState, "createdAtMs")).longValue() + phaseBudget,
                        ReflectionTestUtils.getField(phaseState, "deadlineMs"));
                if (sliceControl) assertEquals(control.equals("slice_alternate") ? 600L : 400L,
                        ReflectionTestUtils.getField(phaseState, "sliceMs"));
                assertFalse(TraceStore.getAll().containsKey("zero100.queryBurst.addedCount"));
                assertEquals(Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d), riskFactors.get(),
                        "observe identical scorer factors in the progressed controls");
            }
            assertEquals(4, calls.get(), "one direct and three lane calls stay fixed");
            assertEquals(requestK, actualTopK.get(question));
            org.junit.jupiter.api.Assertions.assertNotNull(riskFactors.get(), "observe the real scorer factor at the planner boundary");
            for (int i = 0; i < lanes.size(); i++) {
                String lane = lanes.get(i);
                double factor = riskFactors.get().get(lane);
                assertTrue(Double.isFinite(factor) && factor >= 0.25d && factor <= 2.5d);
                double weight = i == selected ? expectedWeight : neutralWeight;
                int uncapped = (int) Math.ceil(16 * factor * weight);
                int expected = Math.max(1, Math.min(Math.max(requestK, 16), uncapped));
                if (progressedControl) assertTrue(uncapped < Math.max(requestK, 16), "progressed control must be uncapped");
                assertEquals(expected, actualTopK.get(seeds.get(i)), "actual provider argument for " + lane);
                System.out.printf(java.util.Locale.ROOT,
                        "TBL07_WEIGHT branch=%s control=%s lane=%s factor=%.4f weight=%.4f requestK=%d uncapped=%d actualK=%d providerCalls=%d%n",
                        branch, control, lane, factor, weight, requestK, uncapped, expected, calls.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "authored,DIVERGE,30,9,9,9", "zero,DIVERGE,30,0,0,0",
            "one,DIVERGE,30,1,1,1", "high,DIVERGE,30,12,12,12",
            "negative,DIVERGE,30,0,0,0", "removed,DIVERGE,30,9,9,9",
            "request,DIVERGE,30,1,1,1", "calibrate,CALIBRATE,0,9,0,0",
            "cross,CROSS_VERIFY,65,9,9,9", "consensus,CONSENSUS,90,9,0,0",
            "disabled,disabled,30,0,0,0", "admission,DIVERGE,30,9,9,0",
            "threshold_authored,CROSS_VERIFY,60,9,9,9", "threshold_early,CONSENSUS,60,9,0,0",
            "duration_authored,CALIBRATE,0,9,0,0", "duration_short,DIVERGE,33,9,9,9"})
    @SuppressWarnings("unchecked")
    void shippedZero100QueryBurstMaxChangesRealExpansionAndProviderSubmissions(
            String control, String phase, int progressPct, int expectedMax,
            int expectedAdded, int expectedSubmitted) throws Throwable {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        var modified = original.deepCopy();
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params");
        boolean thresholdControl = control.startsWith("threshold_");
        boolean durationControl = control.startsWith("duration_");
        String selectedKey = durationControl ? "maxMinutes" : thresholdControl ? "consensusStartPct" : "queryBurstMax";
        String key = "search.zero100." + selectedKey;
        assertEquals(durationControl ? 100 : thresholdControl ? 80 : 9, params.path(key).asInt());
        switch (control) {
            case "zero" -> params.put(key, 0);
            case "one" -> params.put(key, 1);
            case "threshold_early" -> params.put(key, 56);
            case "duration_short" -> params.put(key, 1);
            case "high" -> params.put(key, 99);
            case "negative" -> params.put(key, -1);
            case "removed" -> params.remove(key);
            default -> { }
        }
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the selected raw duration, burst limit or phase threshold may change");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        if (control.equals("request")) ctx.putPlanOverride(key, 1);
        applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
        if (control.equals("removed")) assertEquals(null, ctx.getPlanOverride(key));
        else assertEquals(control.equals("request") ? 1 : params.path(key).asInt(), ctx.planInt(key, -99));
        GuardContextHolder.set(ctx);
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        props.setEngineEnabled(!control.equals("disabled"));
        var registry = org.mockito.Mockito.spy(new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props));
        String question = "alpha beta gamma delta epsilon";
        long firstTouchNs = System.nanoTime();
        var first = registry.touch(com.example.lms.trace.LogCorrelation.sessionId(), question,
                ctx.planLong("search.zero100.maxMinutes", props.getMaxMinutes()),
                ctx.planLong("search.zero100.sliceMs", props.getSliceMs()),
                ctx.planLong("search.zero100.webTimeboxMs", props.getWebCallTimeboxMs()),
                ctx.planLong("search.zero100.backoffHardCapMs", props.getBackoffHardCapMs()));
        assertEquals("CALIBRATE", ai.abandonware.nova.orch.zero100.Zero100BranchScheduler.schedule(first, ctx).phase().name());
        var sessions = (Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions");
        assertEquals(1, sessions.size());
        Object state = sessions.values().iterator().next();
        long sessionBudget = ((Number) ReflectionTestUtils.getField(state, "budgetMs")).longValue();
        if (durationControl) assertEquals(control.equals("duration_short") ? 60_000L : 6_000_000L, sessionBudget);
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(props, registry,
                new org.springframework.mock.env.MockEnvironment());
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ExecutorService executor = newSingleSearchWorker("tbl07-zero100-query-burst");
        configureAsyncTimeoutRetrieval(retriever, executor, control.equals("admission") ? 3 : 60, 500);
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        ReflectionTestUtils.setField(retriever, "ragAnchorEnabled", false);
        ReflectionTestUtils.setField(retriever, "retrievalBudgetGovernor", null);
        ReflectionTestUtils.setField(retriever, "offlineTextureSnapshotLoader", null);
        var planner = org.mockito.Mockito.mock(SelfAskPlanner.class);
        org.mockito.Mockito.when(planner.generateThreeLanes(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, "strict fixture", Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.ER, "relaxed fixture", Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, "explore fixture", Map.of())));
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", planner);
        var expanded = new java.util.concurrent.atomic.AtomicReference<List<String>>(List.of());
        AtomicInteger expanderMax = new AtomicInteger(-1);
        AtomicInteger expanderMin = new AtomicInteger(-1);
        var expander = org.mockito.Mockito.spy(new com.example.lms.nova.burst.QueryBurstExpander());
        org.mockito.Mockito.doAnswer(invocation -> {
            expanderMin.set(invocation.getArgument(1));
            expanderMax.set(invocation.getArgument(2));
            List<String> values = (List<String>) invocation.callRealMethod();
            expanded.set(List.copyOf(values));
            return values;
        }).when(expander).expand(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        ReflectionTestUtils.setField(retriever, "zero100QueryBurstExpander", expander);
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{question});
        org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
            if (props.isEngineEnabled()) {
                assertEquals(phase, TraceStore.get("zero100.phase"), "actual registry touch and scheduler phase");
                assertEquals(progressPct, TraceStore.get("zero100.progressPct"));
                assertEquals(expectedMax, TraceStore.get("zero100.queryBurst.max"));
            } else assertFalse(TraceStore.getAll().containsKey("zero100.phase"));
            return retriever.retrieve(new Query(question));
        });
        // Only this newly constructed test session is aged; touch returns are never mocked.
        long elapsedFixtureMs = durationControl ? 20_000L : sessionBudget * progressPct / 100;
        long agedStart = System.currentTimeMillis() - elapsedFixtureMs;
        ReflectionTestUtils.setField(state, "createdAtMs", agedStart);
        try {
            if (durationControl) assertTrue(System.nanoTime() - firstTouchNs < TimeUnit.SECONDS.toNanos(60),
                    "the initial real session must remain unexpired before the aged second touch");
            assertEquals(List.of(), aspect.aroundChatEntry(pjp));
            org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(props.isEngineEnabled() ? 2 : 1))
                    .touch(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            org.junit.jupiter.api.Assertions.assertSame(state, sessions.values().iterator().next());
            if (props.isEngineEnabled()) assertEquals(agedStart + sessionBudget, ReflectionTestUtils.getField(state, "deadlineMs"));
            assertEquals(expectedAdded, expanded.get().size(), "real expander result count");
            assertEquals(expectedAdded, new java.util.HashSet<>(expanded.get()).size(), "unique expansion candidates");
            long submitted = provider.queries.stream().filter(expanded.get()::contains).count();
            assertEquals(expectedSubmitted, submitted, "actual provider submissions of expansion candidates");
            assertEquals(1, java.util.Collections.frequency(provider.queries, question));
            if (expectedAdded > 0) {
                assertEquals(expectedMax, expanderMax.get());
                assertEquals(Math.min(3, expectedMax), expanderMin.get());
                assertEquals(expectedAdded, TraceStore.get("zero100.queryBurst.addedCount"));
                assertEquals(expectedAdded, TraceStore.get("zero100.queryBurst.seedCount"));
                assertEquals(expectedAdded, ((List<?>) TraceStore.get("zero100.queryBurst.seedHashes")).size());
            } else {
                assertEquals(-1, expanderMax.get());
                assertFalse(TraceStore.getAll().containsKey("zero100.queryBurst.addedCount"));
            }
            if (control.equals("admission")) {
                assertEquals(4, provider.calls.get());
                var attempts = (List<Map<String, Object>>) TraceStore.get("selfask.requery.attempts");
                assertEquals(9L, attempts.stream().filter(r -> "skipped:lane_budget_exhausted".equals(r.get("failureClass"))).count());
            }
            System.out.printf("TBL07_BURST control=%s key=%s phase=%s progress=%d configuredMax=%d expanderMax=%d added=%d submitted=%d providerCalls=%d actualTouchCalls=%d%n",
                    control, selectedKey, phase, progressPct, expectedMax, expanderMax.get(), expectedAdded,
                    submitted, provider.calls.get(), props.isEngineEnabled() ? 2 : 1);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static SelfAskWebSearchRetriever newRetriever(WebSearchProvider provider) {
        SelfAskWebSearchRetriever retriever = new SelfAskWebSearchRetriever(provider, null, null, null);
        ReflectionTestUtils.setField(retriever, "webTopK", 3);
        ReflectionTestUtils.setField(retriever, "overallTopK", 3);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 1);
        ReflectionTestUtils.setField(retriever, "maxDepth", 1);
        ReflectionTestUtils.setField(retriever, "perRequestTimeoutMs", 500);
        ReflectionTestUtils.setField(retriever, "selfAskTimeoutSec", 1);
        ReflectionTestUtils.setField(retriever, "finalTopK", 3);
        ReflectionTestUtils.setField(retriever, "laneTopK", 1);
        ReflectionTestUtils.setField(retriever, "followupsPerLevel", 1);
        ReflectionTestUtils.setField(retriever, "maxApiCallsPerQuery", 8);
        return retriever;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "neutral_on,true,false,false,true,1,true", "neutral_off,false,false,false,true,1,false",
            "plan_on,true,true,false,true,1,true", "plan_off,false,true,false,true,1,true",
            "hint_on,true,false,true,true,1,true", "hint_off,false,false,true,true,1,true",
            "engine_mask_on,true,true,true,false,1,false", "engine_mask_off,false,true,true,false,1,false",
            "wide_on,true,false,false,true,3,true", "wide_off,false,false,false,true,3,false",
            "empty_on,true,false,false,true,0,true", "empty_off,false,false,false,true,0,false"})
    @SuppressWarnings("unchecked")
    void shippedZero100EnabledControlsActualAdmissionWithActivationAndBudgetMasks(
            String control, boolean rawEnabled, boolean planFallback, boolean hintFallback,
            boolean engineEnabled, int cap, boolean expectedActive) throws Throwable {
        TraceStore.clear();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "search.zero100.enabled";
        assertTrue(original.path("params").path(key).asBoolean());
        var modified = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawEnabled);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                .set(key, original.path("params").path(key));
        assertEquals(original, restored, "only the raw activation flag differs within each masking pair");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                    return new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override public String getFilename() { return "zero100.v1.yaml"; }
                    };
                }
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        GuardContext ctx = new GuardContext();
        String requestPlanId = planFallback ? "zero100.v1" : "safe.v1";
        ctx.setPlanId(requestPlanId);
        applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
        assertEquals(rawEnabled, ctx.planBool(key, !rawEnabled));
        assertEquals(requestPlanId, ctx.getPlanId(), "request identity is distinct from loaded YAML identity");
        assertEquals("zero100.v1", TraceStore.get("plan.id"));
        assertEquals(0.34d, ctx.planDouble("search.zero100.strictCallBudgetRatio", -1.0d));
        assertEquals(0.33d, ctx.planDouble("search.zero100.relaxedCallBudgetRatio", -1.0d));
        assertEquals(0.33d, ctx.planDouble("search.zero100.exploreCallBudgetRatio", -1.0d));
        var props = new ai.abandonware.nova.config.Zero100EngineProperties();
        props.setEngineEnabled(engineEnabled);
        var registry = new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props);
        var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(
                props, registry, new org.springframework.mock.env.MockEnvironment());
        CountingProvider provider = new CountingProvider();
        SelfAskWebSearchRetriever retriever = newRetriever(provider);
        ExecutorService executor = newSingleSearchWorker("tbl07-zero100-enabled");
        configureAsyncTimeoutRetrieval(retriever, executor, cap, 500);
        var planner = org.mockito.Mockito.mock(SelfAskPlanner.class);
        List<String> seeds = List.of("strict fixture", "relaxed fixture", "explore fixture");
        org.mockito.Mockito.when(planner.generateThreeLanes(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.BQ, seeds.get(0), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.ER, seeds.get(1), Map.of()),
                new SelfAskPlanner.SubQuestion(SelfAskPlanner.SubQuestionType.RC, seeds.get(2), Map.of())));
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", planner);
        String question = (hintFallback ? "zero100 " : "") + "alpha beta gamma delta epsilon";
        var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{question});
        org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
            assertEquals(expectedActive, Boolean.TRUE.equals(TraceStore.get("zero100.enabled")));
            assertEquals(expectedActive ? 1 : 0,
                    ((Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions")).size());
            if (expectedActive) {
                assertEquals("CALIBRATE", TraceStore.get("zero100.phase"));
                assertEquals("BQ", TraceStore.get("zero100.activeLane"));
                assertEquals(Map.of("BQ", 0.34d, "ER", 0.33d, "RC", 0.33d),
                        TraceStore.get("zero100.branch.callRatios"));
            } else {
                assertFalse(TraceStore.getAll().containsKey("zero100.branch.callRatios"));
            }
            return retriever.retrieve(new Query(question));
        });
        GuardContextHolder.set(ctx);
        try {
            assertEquals(List.of(), aspect.aroundChatEntry(pjp));
            int bqCalls = cap == 3 || (cap == 1 && expectedActive) ? 1 : 0;
            int erCalls = cap == 3 || (cap == 1 && !expectedActive) ? 1 : 0;
            int rcCalls = cap == 3 ? 1 : 0;
            int[] expected = {bqCalls, erCalls, rcCalls};
            for (int i = 0; i < seeds.size(); i++) {
                assertEquals(expected[i], java.util.Collections.frequency(provider.queries, seeds.get(i)),
                        "actual provider admission for " + control + ":" + i);
            }
            assertEquals(1, java.util.Collections.frequency(provider.queries, question));
            assertEquals(1 + cap, provider.calls.get(), "direct attempt plus fixed expansion allowance");
            assertEquals(3, TraceStore.get("selfask.branch3.seedCount"));
            Object attemptObject = TraceStore.get("selfask.requery.attempts");
            List<Map<String, Object>> attempts = attemptObject instanceof List<?>
                    ? (List<Map<String, Object>>) attemptObject : List.of();
            long skips = attempts.stream().filter(row -> "skipped:lane_budget_exhausted".equals(row.get("failureClass"))).count();
            assertEquals(expectedActive ? 3L - cap : 0L, skips);
            org.mockito.Mockito.verify(pjp, org.mockito.Mockito.times(1)).proceed();
            org.mockito.Mockito.verify(planner, org.mockito.Mockito.times(1)).generateThreeLanes(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyMap());
            System.out.printf("TBL07_ENABLED control=%s raw=%s requestPlanFallback=%s hint=%s engine=%s active=%s cap=%d BQ=%d ER=%d RC=%d skips=%d providerCalls=%d externalRequests=0%n",
                    control, rawEnabled, planFallback, hintFallback, engineEnabled, expectedActive,
                    cap, bqCalls, erCalls, rcCalls, skips, provider.calls.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            GuardContextHolder.clear();
            TraceStore.clear();
        }
    }

    private static void configureAsyncTimeoutRetrieval(
            SelfAskWebSearchRetriever retriever,
            ExecutorService executor,
            int maxApiCalls,
            int timeoutMs) {
        ReflectionTestUtils.setField(retriever, "searchExecutor", executor);
        ReflectionTestUtils.setField(retriever, "selfAskEnabled", true);
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", false);
        ReflectionTestUtils.setField(retriever, "logicDagEnabled", false);
        ReflectionTestUtils.setField(retriever, "branchQualityEnabled", false);
        ReflectionTestUtils.setField(retriever, "useLlmSeeds", false);
        ReflectionTestUtils.setField(retriever, "useLlmFollowups", false);
        ReflectionTestUtils.setField(retriever, "firstHitStopThreshold", 99);
        ReflectionTestUtils.setField(retriever, "maxDepth", 1);
        ReflectionTestUtils.setField(retriever, "perRequestTimeoutMs", timeoutMs);
        ReflectionTestUtils.setField(retriever, "selfAskTimeoutSec", 1);
        ReflectionTestUtils.setField(retriever, "maxApiCallsPerQuery", maxApiCalls);
    }

    private static ExecutorService newSingleSearchWorker(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void enableThreeWayPlanner(SelfAskWebSearchRetriever retriever) {
        ReflectionTestUtils.setField(retriever, "threeWayEnabled", true);
        ReflectionTestUtils.setField(retriever, "threeWayPlanner", new SelfAskPlanner(null, null));
        ReflectionTestUtils.setField(retriever, "logicDagMaxNodes", 5);
        ReflectionTestUtils.setField(retriever, "logicDagPruneDuplicates", true);
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

    private static List<?> invokeBranch3Seeds(SelfAskWebSearchRetriever retriever, String query) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "branch3Seeds", String.class, long.class, boolean.class, double.class, Map.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(retriever, query, 1000L, true, 0.2d, Map.of());
    }

    private static List<String> laneSeedLanes(List<?> seeds) {
        return seeds.stream()
                .map(seed -> {
                    try {
                        return String.valueOf(recordValue(seed, "lane"));
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private static Object invokeSafeSearchAttempt(
            SelfAskWebSearchRetriever retriever,
            String query,
            int topK) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod("safeSearchAttempt", String.class, int.class);
        method.setAccessible(true);
        return method.invoke(retriever, query, topK);
    }

    private static Object invokeHardTimeout(
            SelfAskWebSearchRetriever retriever,
            Future<?> future,
            long timeoutMs,
            String query) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "getWithHardTimeout", Future.class, long.class, String.class);
        method.setAccessible(true);
        return method.invoke(retriever, future, timeoutMs, query);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> zero100Budgets(int totalCalls) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod("zero100LaneBudgets", int.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, totalCalls);
    }

    private static int sumRemaining(Map<String, Object> budgets) throws Exception {
        int total = 0;
        for (Object budget : budgets.values()) {
            total += remaining(budget);
        }
        return total;
    }

    private static int sumInitial(Map<String, Object> budgets) throws Exception {
        int total = 0;
        for (Object budget : budgets.values()) {
            total += ((Number) recordValue(budget, "initial")).intValue();
        }
        return total;
    }

    private static String zero100BurstSeed(String parentQuery) throws Exception {
        Method method = SelfAskWebSearchRetriever.class.getDeclaredMethod(
                "zero100BurstSeed", String.class, java.util.List.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, parentQuery, List.of(), "RC");
    }

    private static int remaining(Object budget) throws Exception {
        return ((Number) recordValue(budget, "remaining")).intValue();
    }

    private static Object recordValue(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static final class ThrowingProvider implements WebSearchProvider {
        private final String message;

        private ThrowingProvider(String message) {
            this.message = message;
        }

        @Override
        public List<String> search(String query, int topK) {
            throw new IllegalStateException(message);
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "throwing";
        }
    }

    private static final class CountingProvider implements WebSearchProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public List<String> search(String query, int topK) {
            calls.incrementAndGet();
            queries.add(query);
            return List.of();
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "counting";
        }
    }

    private static final class CancellingProvider implements WebSearchProvider {

        @Override
        public List<String> search(String query, int topK) {
            throw new CancellationException("cancelled ownerToken=fake-token");
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "cancelling";
        }
    }

    private static final class TimeoutRecordingFuture implements Future<Object> {
        final AtomicInteger cancelCalls = new AtomicInteger();
        Boolean lastMayInterruptIfRunning;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            lastMayInterruptIfRunning = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() > 0;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new TimeoutException("forced hard timeout");
        }
    }

    private static final class RecordingNoRunExecutor extends AbstractExecutorService {
        final AtomicInteger executeCalls = new AtomicInteger();
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            executeCalls.incrementAndGet();
        }
    }

    private static final class InterruptedRecordingFuture implements Future<Object> {
        final AtomicInteger cancelCalls = new AtomicInteger();
        Boolean lastMayInterruptIfRunning;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            lastMayInterruptIfRunning = mayInterruptIfRunning;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() > 0;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() throws InterruptedException {
            throw new InterruptedException("forced interrupted wait");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("forced interrupted wait");
        }
    }

    private static final class BlockingAfterFirstProvider implements WebSearchProvider {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch asyncStarted = new CountDownLatch(1);
        final CountDownLatch asyncInterrupted = new CountDownLatch(1);
        final CountDownLatch asyncExited = new CountDownLatch(1);
        final CountDownLatch releaseNormally = new CountDownLatch(1);
        private final CountDownLatch exitAfterInterrupt;

        private BlockingAfterFirstProvider(CountDownLatch exitAfterInterrupt) {
            this.exitAfterInterrupt = exitAfterInterrupt;
        }

        @Override
        public List<String> search(String query, int topK) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return List.of();
            }
            asyncStarted.countDown();
            try {
                releaseNormally.await();
                return List.of();
            } catch (InterruptedException interrupted) {
                asyncInterrupted.countDown();
                if (exitAfterInterrupt != null) {
                    boolean restore = false;
                    while (true) {
                        try {
                            exitAfterInterrupt.await();
                            break;
                        } catch (InterruptedException repeated) {
                            restore = true;
                        }
                    }
                    if (restore) {
                        Thread.currentThread().interrupt();
                    }
                }
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                asyncExited.countDown();
            }
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "blocking-after-first";
        }
    }

    private static final class RecordingProvider implements WebSearchProvider {
        private final String originalQuery;
        final AtomicInteger calls = new AtomicInteger();

        private RecordingProvider(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        @Override
        public List<String> search(String query, int topK) {
            int call = calls.incrementAndGet();
            if (originalQuery.equals(query)) {
                return List.of();
            }
            return List.of("logic-dag-snippet-" + call);
        }

        @Override
        public SearchResult searchWithTrace(String query, int topK) {
            return new SearchResult(search(query, topK), new NaverSearchService.SearchTrace());
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "recording";
        }
    }

    private static final class FailingLogicDagRetriever extends SelfAskWebSearchRetriever {
        private FailingLogicDagRetriever(WebSearchProvider provider) {
            super(provider, null, null, null);
        }

        @Override
        LogicRagPlanner logicRagPlanner() {
            return new LogicRagPlanner(true);
        }
    }
}
