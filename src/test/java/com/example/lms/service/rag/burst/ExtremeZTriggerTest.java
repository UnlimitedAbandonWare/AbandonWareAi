package com.example.lms.service.rag.burst;

import com.example.lms.orchestration.ExecutionPlan;
import com.example.lms.orchestration.StrategyConflictResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.SelfAskPlanner;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtremeZTriggerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void extremeZFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String handler = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java"));
        String expander = Files.readString(Path.of(
                "main/java/com/example/lms/nova/burst/QueryBurstExpander.java"));
        String trigger = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/burst/ExtremeZTrigger.java"));

        assertStage(handler, "ExtremeZSystemHandler", "plan.trace");
        assertStage(handler, "ExtremeZSystemHandler", "vectorRetriever");
        assertStage(handler, "ExtremeZSystemHandler", "parallel.task");
        assertStage(handler, "ExtremeZSystemHandler", "parallel.interrupted");
        assertTrue(expander.contains("TraceStore.put(\"extremeZ.burstExpand.bypassReason\""),
                "planner burst fail-soft should be traced by the active QueryBurstExpander");
        assertStage(handler, "ExtremeZSystemHandler", "web");
        assertStage(handler, "ExtremeZSystemHandler", "vector");
        assertStage(handler, "ExtremeZSystemHandler", "rrf");
        assertStage(trigger, "ExtremeZTrigger", "trace");
        assertTrue(handler.contains("TraceStore.put(\"extremeZ.interrupted\""),
                "interrupted fanout should expose the canonical extremeZ.interrupted breadcrumb");
        assertTrue(handler.contains("TraceStore.put(\"extremeZ.outCount\""),
                "ExtremeZ execution should expose the canonical outCount breadcrumb");
    }

    @Test
    void directSystemHandlerWithoutExecutorLeavesSequentialCancelShieldBreadcrumb() {
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, new ExtremeZProperties()),
                null,
                null,
                null,
                null,
                null,
                new ExtremeZProperties(),
                null);

        assertNotNull(handler);
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.executor.cancelShieldWrapped"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.cancelShieldWrapped"));
        assertEquals("no_executor_sequential", TraceStore.get("extremeZ.cancelShield.skipReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.systemHandler.executorWired"));
    }

    @Test
    void lowRecallActivatesExtremeZAndRecordsDecision() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(3);
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "RAG evidence starvation debug",
                1,
                0.80d,
                0.0d,
                false);

        assertTrue(decision.activate());
        assertTrue(decision.lowRecall());
        assertEquals("lowRecall", decision.reason());
        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, decision.plan().primaryMode());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trigger.lowRecall"));
        assertEquals("EXTREMEZ", TraceStore.get("extremez.trigger.plan.primaryMode"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.activated"));
        assertEquals("lowRecall", TraceStore.get("extremeZ.triggerReasons"));
    }

    @Test
    void defaultPropertiesActivateLowRecallTrigger() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMinBaseDocs(3);
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "RAG evidence starvation debug",
                0,
                0.80d,
                0.0d,
                false);

        assertTrue(decision.activate());
        assertTrue(decision.lowRecall());
        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, decision.plan().primaryMode());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trigger.enabled"));
        assertEquals("EXTREMEZ", TraceStore.get("extremez.trigger.plan.primaryMode"));
    }

    @Test
    void lowAuthorityAndContradictionEscalateToOverdrivePlan() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "conflicting official claims",
                5,
                0.20d,
                0.85d,
                false);

        assertTrue(decision.activate());
        assertFalse(decision.lowRecall());
        assertTrue(decision.lowAuthority());
        assertTrue(decision.contradiction());
        assertEquals(ExecutionPlan.PrimaryMode.OVERDRIVE, decision.plan().primaryMode());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trigger.lowAuthority"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trigger.contradiction"));
    }

    @Test
    void extremeZWinsWhenEverySpecialModeSignalFires() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(3);
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "high risk tail event",
                0,
                0.10d,
                0.90d,
                true);

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, decision.plan().primaryMode());
        assertTrue(decision.plan().extremeZEnabled());
        assertFalse(decision.plan().overdriveEnabled());
        assertFalse(decision.plan().hypernovaEnabled());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.trigger.highRiskTail"));
    }

    @Test
    void highRiskTailAloneRoutesToHypernovaWithoutOtherModes() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(0);
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "high risk tail event",
                5,
                0.90d,
                0.0d,
                true);

        assertEquals(ExecutionPlan.PrimaryMode.HYPERNOVA, decision.plan().primaryMode());
        assertFalse(decision.plan().extremeZEnabled());
        assertFalse(decision.plan().overdriveEnabled());
        assertTrue(decision.plan().hypernovaEnabled());
    }

    @Test
    void traceReasonUsesRedactedLabelForResolverReason() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        String unsafeReason = "ownerToken=" + "s" + "k-" + "redactioncontract1234567890";
        StrategyConflictResolver resolver = new StrategyConflictResolver() {
            @Override
            public ExecutionPlan resolve(Signals signals) {
                return new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of(unsafeReason),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of());
            }
        };
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props, resolver);

        trigger.evaluate("redaction probe", 1, 0.80d, 0.0d, false);

        Object tracedReason = TraceStore.get("extremez.trigger.reason");
        assertNotNull(tracedReason);
        assertTrue(String.valueOf(tracedReason).startsWith("hash:"));
        assertFalse(String.valueOf(tracedReason).contains("ownerToken"));
    }

    @Test
    void systemHandlerTraceReasonUsesRedactedLabel() {
        String unsafeReason = "Authorization=Bearer " + "s" + "k-" + "redactioncontract1234567890";
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, new ExtremeZProperties()) {
            @Override
            public Decision evaluate(String query,
                                     int baseDocCount,
                                     double authorityScore,
                                     double contradictionScore,
                                     boolean highRiskTail) {
                return new Decision(
                        false,
                        false,
                        false,
                        false,
                        false,
                        unsafeReason,
                        ExecutionPlan.normal());
            }
        };
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                trigger,
                null,
                null,
                null,
                null,
                null,
                new ExtremeZProperties());

        handler.plan("redaction probe", 1, 0.80d, 0.0d, false);

        Object tracedReason = TraceStore.get("extremez.systemHandler.plan.reason");
        assertNotNull(tracedReason);
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
        assertTrue(String.valueOf(tracedReason).startsWith("hash:"));
        assertFalse(String.valueOf(tracedReason).contains("Authorization"));
    }

    @Test
    void systemHandlerPlanTraceFailureKeepsRedactedBreadcrumb() {
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, new ExtremeZProperties()) {
            @Override
            public Decision evaluate(String query,
                                     int baseDocCount,
                                     double authorityScore,
                                     double contradictionScore,
                                     boolean highRiskTail) {
                return new Decision(
                        false,
                        false,
                        false,
                        false,
                        false,
                        "ownerToken=secret",
                        null);
            }
        };
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                trigger,
                null,
                null,
                null,
                null,
                null,
                new ExtremeZProperties());

        ExtremeZTrigger.Decision decision = handler.plan("redaction probe", 1, 0.80d, 0.0d, false);

        assertFalse(decision.activate());
        assertEquals("NullPointerException", TraceStore.get("extremez.systemHandler.planTraceError"));
        assertTrue(String.valueOf(TraceStore.get("extremez.systemHandler.planTraceErrorHash")).startsWith("hash:"));
        assertTrue(TraceStore.get("extremez.systemHandler.planTraceErrorLength") instanceof Integer);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void systemHandlerPlanWritesUnifiedActivationAndTriggerReason() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(3);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                null,
                null,
                null,
                null,
                null,
                props);

        ExtremeZTrigger.Decision decision = handler.plan("RAG evidence", 0, 0.80d, 0.0d, false);

        assertTrue(decision.activate());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("lowRecall", TraceStore.get("extremez.triggerReasons"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.activated"));
        assertEquals("lowRecall", TraceStore.get("extremeZ.triggerReasons"));
        assertEquals("", TraceStore.get("extremeZ.bypassReason"));
    }

    @Test
    void systemHandlerPlanIgnoresStaleOverdriveFlagWithoutResolverPrimary() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(3);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                null,
                null,
                null,
                null,
                null,
                props);
        TraceStore.put("overdrive.activated", true);

        ExtremeZTrigger.Decision decision = handler.plan("RAG evidence", 0, 0.80d, 0.0d, false);

        assertTrue(decision.activate());
        assertEquals("lowRecall", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.activated"));
        assertEquals("lowRecall", TraceStore.get("extremez.triggerReasons"));
    }

    @Test
    void systemHandlerPlanSuppressesExtremeZWhenResolverPrimaryBelongsToOverdrive() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(3);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                null,
                null,
                null,
                null,
                null,
                props);
        TraceStore.put("overdrive.activated", true);
        TraceStore.put("routing.executionPlan.primaryMode", "OVERDRIVE");

        ExtremeZTrigger.Decision decision = handler.plan("RAG evidence", 0, 0.80d, 0.0d, false);

        assertFalse(decision.activate());
        assertEquals("suppressed_by_conflict_resolver", decision.reason());
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
        assertEquals("conflict_resolver_primary=OVERDRIVE", TraceStore.get("extremez.suppressedBy"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.activated"));
        assertEquals("suppressed_by_conflict_resolver", TraceStore.get("extremeZ.bypassReason"));
    }

    @Test
    void triggerCanScoreContradictionCandidatesDirectly() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setEnabled(1);
        props.setMinBaseDocs(5);
        props.setContradictionThreshold(0.50d);
        com.example.lms.service.rag.energy.ContradictionScorer scorer =
                mock(com.example.lms.service.rag.energy.ContradictionScorer.class);
        when(scorer.score(any(), any())).thenReturn(0.90d);
        ExtremeZTrigger trigger = new ExtremeZTrigger(scorer, props);

        ExtremeZTrigger.Decision decision = trigger.evaluate(
                "conflicting claims",
                8,
                0.90d,
                false,
                List.of("A says KPI is 10", "B says KPI is 99"));

        assertTrue(decision.activate());
        assertTrue(decision.contradiction());
        assertEquals(0.90d, (Double) TraceStore.get("extremez.trigger.contradictionScore"), 0.0001d);
        assertEquals(0.90d, (Double) TraceStore.get("extremeZ.trigger.contradictionScore"), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.trigger.activated"));
        assertEquals("contradiction", TraceStore.get("extremeZ.trigger.reason"));
        assertEquals(1, TraceStore.get("extremez.trigger.contradictionPairs"));
    }

    @Test
    void systemHandlerExecutesPlannedBurstRetrievalAndTracesCounts() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setMaxMergedDocs(4);
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " result"));
        });
        ExtremeZTrigger trigger = new ExtremeZTrigger(null, props);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                trigger,
                planner,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props);
        ExtremeZTrigger.Decision decision = new ExtremeZTrigger.Decision(
                true,
                true,
                false,
                false,
                false,
                "lowRecall",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of("lowRecall"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));

        List<Content> out = handler.execute("RAG evidence", decision);

        assertEquals(2, out.size());
        verify(webRetriever, times(2)).retrieve(any(Query.class));
        assertEquals(2, TraceStore.get("extremez.execute.subQueryCount"));
        assertEquals(2, TraceStore.get("extremez.execute.rawCount"));
        assertEquals(2, TraceStore.get("extremez.execute.refinedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.execute.activated"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.activated"));
        assertEquals(2, TraceStore.get("extremez.mergedDocCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.rrfApplied"));
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.count"));
        assertEquals(1, TraceStore.get("extremeZ.burstExpand.min"));
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.max"));
        assertEquals("", TraceStore.get("extremeZ.burstExpand.bypassReason"));
        assertEquals(2, TraceStore.get("extremeZ.subQueryCount"));
        assertEquals(2, TraceStore.get("extremeZ.mergedDocCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.rrfApplied"));
        assertEquals("", TraceStore.get("extremeZ.bypassReason"));
    }

    @Test
    void systemHandlerSkipsWhenResolvedPlanBelongsToOverdrive() {
        ExtremeZProperties props = new ExtremeZProperties();
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                null,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props);
        ExtremeZTrigger.Decision overdriveDecision = new ExtremeZTrigger.Decision(
                true,
                false,
                true,
                true,
                false,
                "lowAuthority+contradiction",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.OVERDRIVE,
                        false,
                        true,
                        false,
                        List.of("lowAuthority", "contradiction"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));

        List<Content> out = handler.execute("conflicting claims", overdriveDecision);

        assertTrue(out.isEmpty());
        verify(webRetriever, times(0)).retrieve(any(Query.class));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.execute.skipped"));
        assertEquals("plan_OVERDRIVE", TraceStore.get("extremez.execute.reason"));
        assertEquals("OVERDRIVE", TraceStore.get("extremez.suppressedBy"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremez.activated"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.activated"));
        assertEquals("plan_OVERDRIVE", TraceStore.get("extremeZ.bypassReason"));
    }

    @Test
    void systemHandlerTracesAndSkipsUnexpectedWebRuntimeFailures() {
        ExtremeZProperties props = new ExtremeZProperties();
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenThrow(new NullPointerException("raw query ownerToken=secret"));
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                null,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props);

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertTrue(out.isEmpty());
        assertEquals("NullPointerException", TraceStore.get("extremez.execute.webError"));
        assertTrue(String.valueOf(TraceStore.get("extremez.execute.webErrorHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void systemHandlerFallsBackToMergedResultsWhenRrfThrowsUnexpectedRuntimeFailure() {
        ExtremeZProperties props = new ExtremeZProperties();
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", props.getMaxSubQueries())).thenReturn(List.of("RAG evidence", "RAG source"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " result"));
        });
        WeightedReciprocalRankFuser rrf = mock(WeightedReciprocalRankFuser.class);
        when(rrf.fuse(any(), anyInt())).thenThrow(new ClassCastException("raw source ownerToken=secret"));
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                rrf,
                null,
                props);

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertEquals(2, out.size());
        assertEquals("ClassCastException", TraceStore.get("extremez.execute.rrfError"));
        assertTrue(String.valueOf(TraceStore.get("extremez.execute.rrfErrorHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void systemHandlerFansOutRetrievalThroughExecutorWhenAvailable() throws Exception {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setParallelTimeoutMs(1_500);
        RecordingExecutor executor = new RecordingExecutor();
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " result"));
        });
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props,
                executor);
        ExtremeZTrigger.Decision decision = new ExtremeZTrigger.Decision(
                true,
                true,
                false,
                false,
                false,
                "lowRecall",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of("lowRecall"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));

        List<Content> out = handler.execute("RAG evidence", decision);

        assertEquals(2, out.size());
        assertEquals(4, executor.executedTaskCount);
        assertEquals(4, TraceStore.get("ops.cancelShield.invokeAll.timeout.tasks"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.execute.parallel.used"));
        assertEquals(4, TraceStore.get("extremez.execute.parallel.taskCount"));
        assertEquals(4, TraceStore.get("extremez.parallelBranchCount"));
        assertEquals(1_500L, TraceStore.get("extremez.timeoutMs"));
        assertEquals(4, TraceStore.get("extremeZ.parallelBranchCount"));
        assertEquals(1_500L, TraceStore.get("extremeZ.timeoutMs"));
    }

    @Test
    void systemHandlerDefaultFanoutCapsPlannerAtTwelveSubQueriesAndTwentyFourBranches() {
        ExtremeZProperties props = new ExtremeZProperties();
        RecordingExecutor executor = new RecordingExecutor();
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        java.util.List<String> planned = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            planned.add("RAG evidence lane " + i);
        }
        when(planner.plan("RAG evidence", 12)).thenReturn(planned);
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " web"));
        });
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props,
                executor);

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertEquals(12, out.size());
        assertTrue(out.size() <= props.getMaxMergedDocs());
        assertEquals(12, TraceStore.get("extremez.execute.subQueryCount"));
        assertEquals(24, TraceStore.get("extremez.execute.parallel.taskCount"));
        assertEquals(24, TraceStore.get("extremez.parallelBranchCount"));
        assertEquals(12, TraceStore.get("extremez.execute.rawCount"));
        assertEquals(12, TraceStore.get("extremez.execute.refinedCount"));
        assertEquals(12, TraceStore.get("extremeZ.outCount"));
        assertEquals(12, TraceStore.get("extremeZ.subQueryCount"));
        assertEquals(24, TraceStore.get("extremeZ.parallelBranchCount"));
        assertEquals(12, TraceStore.get("extremeZ.mergedDocCount"));
        assertEquals(12, TraceStore.get("extremeZ.parallelFanout.queryCount"));
        assertEquals(12, TraceStore.get("extremeZ.parallelFanout.mergedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.rrfApplied"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremez.execute.parallel.used"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.interruptPropagated"));
        assertEquals(24, TraceStore.get("ops.cancelShield.invokeAll.timeout.tasks"));
        verify(planner).plan("RAG evidence", 12);
    }

    @Test
    void systemHandlerShieldsDirectRawExecutorTimedFanout() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setParallelTimeoutMs(1_500);
        RecordingExecutor executor = new RecordingExecutor();
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        when(webRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            return List.of(Content.from(query.text() + " result"));
        });
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                webRetriever,
                null,
                new WeightedReciprocalRankFuser(60, null, ""),
                null,
                props,
                executor);
        ExtremeZTrigger.Decision decision = new ExtremeZTrigger.Decision(
                true,
                true,
                false,
                false,
                false,
                "lowRecall",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of("lowRecall"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));

        List<Content> out = handler.execute("RAG evidence", decision);

        assertEquals(2, out.size());
        assertEquals(4, TraceStore.get("ops.cancelShield.invokeAll.timeout.tasks"));
        assertEquals("extremezSystemHandler".length(),
                TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerLength"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.cancelShieldWrapped"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.interruptPropagated"));
        assertTrue(TraceStore.get("extremeZ.timeBudgetConsumedMs") instanceof Long);
        assertTrue(String.valueOf(TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerHash")).startsWith("hash:"));
        assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.invokeAll.timeout.owner"));
    }

    @Test
    void systemHandlerTimeoutLeavesCancelSuppressedBreadcrumbs() {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setParallelTimeoutMs(1);
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props),
                planner,
                mock(AnalyzeWebSearchRetriever.class),
                null,
                null,
                null,
                props,
                new StallingExecutor());

        List<Content> out = handler.execute("RAG evidence", activeExtremeZDecision());

        assertTrue(out.isEmpty());
        assertEquals(2, TraceStore.get("extremeZ.parallelFanout.queryCount"));
        assertEquals(0, TraceStore.get("extremeZ.parallelFanout.mergedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.cancelSuppressed"));
        assertTrue(((Number) TraceStore.get("extremeZ.cancelSuppressed.count")).intValue() > 0);
        assertEquals("cancel_shield_boundary", TraceStore.get("extremeZ.cancelSuppressed.errorType"));
        assertTrue(((Number) TraceStore.get("extremeZ.cancelShield.interruptsSuppressed")).intValue() > 0);
        assertEquals(Boolean.TRUE, TraceStore.get("extremeZ.cancel.interruptSuppressed"));
        assertEquals(Boolean.FALSE, TraceStore.get("extremeZ.interruptPropagated"));
        assertEquals("cancel_shield_invoke_all", TraceStore.get("timeout.stage"));
        assertTrue(String.valueOf(TraceStore.get("ops.cancelShield.invokeAll.timeout.ownerHash")).startsWith("hash:"));
        assertFalse(TraceStore.getAll().containsKey("ops.cancelShield.invokeAll.timeout.owner"));
    }

    @Test
    void interruptedParallelCallerReturnsWithClearedFlagAndCancelShieldEvidence() throws Exception {
        ExtremeZProperties props = new ExtremeZProperties();
        props.setMaxSubQueries(2);
        props.setParallelTimeoutMs(5_000);
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 2)).thenReturn(List.of("RAG evidence official", "RAG evidence pdf"));
        AnalyzeWebSearchRetriever webRetriever = mock(AnalyzeWebSearchRetriever.class);
        java.util.concurrent.CountDownLatch submitted = new java.util.concurrent.CountDownLatch(1);
        ExtremeZSystemHandler handler = new ExtremeZSystemHandler(
                new ExtremeZTrigger(null, props), planner, webRetriever, null, null, null, props,
                new StallingExecutor(submitted));
        java.util.concurrent.atomic.AtomicReference<Thread> callerThread = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "extremez-r04-interrupted-caller");
            thread.setDaemon(true);
            callerThread.set(thread);
            return thread;
        });
        record Observation(boolean interruptedAfterReturn, List<Content> output, Map<String, Object> trace) {}
        try {
            Future<Observation> returned = caller.submit(() -> {
                TraceStore.clear();
                try {
                    List<Content> output = handler.execute("RAG evidence", activeExtremeZDecision());
                    Map<String, Object> trace = new java.util.LinkedHashMap<>();
                    for (String key : List.of("extremeZ.interrupted", "extremeZ.interruptPropagated",
                            "extremeZ.interrupt.suppressed.reason", "extremeZ.cancel.interruptSuppressed",
                            "ops.cancelShield.invokeAll.timeout.tasks")) {
                        trace.put(key, TraceStore.get(key));
                    }
                    return new Observation(Thread.currentThread().isInterrupted(), output, trace);
                } finally {
                    TraceStore.clear();
                }
            });
            assertTrue(submitted.await(2, TimeUnit.SECONDS), "parallel invokeAll must submit before caller interruption");
            callerThread.get().interrupt();
            Observation observed = returned.get(2, TimeUnit.SECONDS);

            assertTrue(observed.output().isEmpty());
            assertFalse(observed.interruptedAfterReturn(), "the existing CancelShield boundary clears the caller flag");
            assertEquals(Boolean.TRUE, observed.trace().get("extremeZ.interrupted"));
            assertEquals(Boolean.FALSE, observed.trace().get("extremeZ.interruptPropagated"));
            assertEquals("cancel_shield_boundary", observed.trace().get("extremeZ.interrupt.suppressed.reason"));
            assertEquals(Boolean.TRUE, observed.trace().get("extremeZ.cancel.interruptSuppressed"));
            assertEquals(4, observed.trace().get("ops.cancelShield.invokeAll.timeout.tasks"));
            org.mockito.Mockito.verifyNoInteractions(webRetriever);
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS), "only the task-owned caller must terminate");
        }
    }

    @Test
    void systemHandlerInterruptedFanoutSuppressesOuterInterruptPropagation() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java"));
        int catchStart = source.indexOf("} catch (InterruptedException e) {");
        assertTrue(catchStart >= 0, "ExtremeZ fanout InterruptedException catch should stay visible");
        int catchEnd = source.indexOf("        } finally {", catchStart);
        assertTrue(catchEnd > catchStart, "ExtremeZ fanout InterruptedException catch should end before finally");
        String catchBody = source.substring(catchStart, catchEnd);

        assertFalse(catchBody.contains("Thread.currentThread().interrupt();"),
                "ExtremeZ fanout must not poison the caller thread past the CancelShield boundary");
        assertTrue(catchBody.contains("TraceStore.put(\"extremeZ.interruptPropagated\", false);"));
        assertTrue(catchBody.contains("TraceStore.put(\"extremeZ.interrupt.suppressed.reason\", \"cancel_shield_boundary\");"));
        assertTrue(catchBody.contains("TraceStore.put(\"extremeZ.interrupt.suppressed.errorType\", \"cancel_shield_boundary\");"));
        assertTrue(catchBody.contains("TraceStore.put(\"extremeZ.cancelSuppressed.errorType\", \"cancel_shield_boundary\");"));
        assertTrue(catchBody.contains("TraceStore.put(\"extremeZ.cancel.interruptSuppressed\", true);"));
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        int lastTimedInvokeAllTaskCount;
        int executedTaskCount;

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            executedTaskCount++;
            command.run();
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                Collection<? extends Callable<T>> tasks,
                long timeout,
                TimeUnit unit) {
            lastTimedInvokeAllTaskCount = tasks.size();
            return tasks.stream()
                    .map(task -> {
                        FutureTask<T> future = new FutureTask<>(task);
                        future.run();
                        return (Future<T>) future;
                    })
                    .toList();
        }
    }

    private static final class StallingExecutor extends AbstractExecutorService {
        private final java.util.concurrent.CountDownLatch firstSubmission;

        StallingExecutor() {
            this(null);
        }

        StallingExecutor(java.util.concurrent.CountDownLatch firstSubmission) {
            this.firstSubmission = firstSubmission;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public void execute(Runnable command) {
            // Do not run submitted work; this forces the timed invokeAll soft-cancel path.
            if (firstSubmission != null) firstSubmission.countDown();
        }
    }

    private static ExtremeZTrigger.Decision activeExtremeZDecision() {
        return new ExtremeZTrigger.Decision(
                true,
                true,
                false,
                false,
                false,
                "lowRecall",
                new ExecutionPlan(
                        ExecutionPlan.PrimaryMode.EXTREMEZ,
                        true,
                        false,
                        false,
                        List.of("lowRecall"),
                        ExecutionPlan.DEFAULT_STAGES,
                        Map.of()));
    }

    private static void assertStage(String source, String component, String stage) {
        assertTrue(source.contains("log.debug(\"[" + component + "] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing " + component + " fail-soft stage: " + stage);
    }
}
