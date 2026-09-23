package com.example.lms.service.agent;

import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.agent.CfvmSnapshotDto;
import com.example.lms.dto.agent.OverdriveStatusDto;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSnapshotServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void cfvmSnapshotUsesTraceSubsetAndRedactsSensitiveScalars() {
        TraceStore.put("extremez.risk.primaryCause", "provider-disabled synthetic-sensitive-value");
        TraceStore.put("learning.validation.decision", "accepted");
        TraceStore.put("cfvm.boltzmannTemp", 0.42d);
        TraceStore.put("cfvm.tempSource", "trace_anchor_pressure");
        TraceStore.put("cfvm.snapshot.saved", true);
        TraceStore.put("cfvm.snapshot.vectorWrite.enabled", false);
        TraceStore.put("cfvm.snapshot.vectorWrite.skipped", "jpa_snapshot_only");
        TraceStore.put("cfvm.failureRecovery.triggered", true);
        TraceStore.put("cfvm.failureRecovery.failureClass", "timeout_cancel_downgraded");
        TraceStore.put("cfvm.failureRecovery.failureWeight", 0.91d);
        TraceStore.put("cfvm.failureRecovery.cancelTrueDowngraded", true);
        TraceStore.put("cfvm.failureRecovery.timeoutCondition", true);
        TraceStore.put("cfvm.failureRecovery.boltzmannWeight", 0.73d);
        TraceStore.put("cfvm.failureRecovery.retrievalOrderAdjusted", true);
        TraceStore.put("cfvm.failureRecovery.snapshot.saved", true);
        TraceStore.put("cfvm.rawBuffer.weightMode", "BOLTZMANN_LEARNING_ACTIVE");
        TraceStore.put("cfvm.kalloc.recovery.applied", true);
        TraceStore.put("unrelated.rawQuery", "do not export");
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.add(7L, 1L, 2L);
        ReportSnapshotService service = service(new DebugEventStore(), buffer);

        CfvmSnapshotDto snapshot = service.cfvmSnapshot();

        assertEquals(1, snapshot.bufferSize());
        assertTrue(snapshot.currentPatternId() != 0L);
        assertTrue(snapshot.traceKeys().containsKey("extremez.risk.primaryCause"));
        assertTrue(snapshot.traceKeys().containsKey("learning.validation.decision"));
        assertEquals(0.42d, snapshot.traceKeys().get("cfvm.boltzmannTemp"));
        assertEquals("trace_anchor_pressure", snapshot.traceKeys().get("cfvm.tempSource"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.snapshot.saved"));
        assertEquals(false, snapshot.traceKeys().get("cfvm.snapshot.vectorWrite.enabled"));
        assertEquals("jpa_snapshot_only", snapshot.traceKeys().get("cfvm.snapshot.vectorWrite.skipped"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.failureRecovery.triggered"));
        assertEquals("timeout_cancel_downgraded", snapshot.traceKeys().get("cfvm.failureRecovery.failureClass"));
        assertEquals(0.91d, snapshot.traceKeys().get("cfvm.failureRecovery.failureWeight"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.failureRecovery.cancelTrueDowngraded"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.failureRecovery.timeoutCondition"));
        assertEquals(0.73d, snapshot.traceKeys().get("cfvm.failureRecovery.boltzmannWeight"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.failureRecovery.retrievalOrderAdjusted"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.failureRecovery.snapshot.saved"));
        assertEquals("BOLTZMANN_LEARNING_ACTIVE", snapshot.traceKeys().get("cfvm.rawBuffer.weightMode"));
        assertEquals(true, snapshot.traceKeys().get("cfvm.kalloc.recovery.applied"));
        assertFalse(snapshot.traceKeys().containsKey("unrelated.rawQuery"));
        assertFalse(snapshot.toString().contains("synthetic-sensitive-value"));
    }

    @Test
    void overdriveStatusProjectsTraceAndConfiguredThreshold() {
        TraceStore.put("overdrive.activated", true);
        TraceStore.put("overdrive.score", 0.82d);
        TraceStore.put("overdrive.score.threshold.effective", 0.55d);
        TraceStore.put("overdrive.aggressive", true);
        TraceStore.put("overdrive.reason", "threshold");
        TraceStore.put("overdrive.candidates.count", 5);
        TraceStore.put("overdrive.blackbox.restoreAction", "anchor_compression_topup");
        ReportSnapshotService service = service(new DebugEventStore(), new RawMatrixBuffer());

        OverdriveStatusDto status = service.overdriveStatus();

        assertTrue(status.activated());
        assertEquals(0.82d, status.score(), 0.0001d);
        assertEquals(0.55d, status.threshold(), 0.0001d);
        assertTrue(status.aggressive());
        assertEquals("threshold", status.reason());
        assertEquals(5, status.candidateCount());
        assertEquals("anchor_compression_topup", status.blackboxRestoreAction());
    }

    @Test
    void artplateStatusRedactsSensitiveTraceMapKeys() {
        String rawKey = "artplate.ownerToken=secret.query";
        TraceStore.put(rawKey, "provider-disabled");
        TraceStore.put("artplate.selector.rollout.reason", "ownerToken=secret");
        ReportSnapshotService service = service(new DebugEventStore(), new RawMatrixBuffer());

        Map<String, Object> status = service.artplateStatus();

        assertFalse(status.toString().contains(rawKey));
        assertFalse(status.toString().contains("ownerToken"));
        assertFalse(status.toString().contains("secret"));
        assertTrue(status.toString().contains("hash:"));
    }

    @Test
    void traceKpiCountsAgentReportDebugEventsByProbe() {
        DebugEventStore store = new DebugEventStore();
        store.emit(DebugProbeType.AGENT_REPORT_CFVM, DebugEventLevel.INFO,
                "agent_report_cfvm_snapshot", "Agent report: CFVM snapshot requested",
                "ReportSnapshotServiceTest", Map.of("bufferSize", 1), null);
        store.emit(DebugProbeType.AGENT_REPORT_GATES, DebugEventLevel.INFO,
                "agent_report_gates_summary", "Agent report: gates summary requested",
                "ReportSnapshotServiceTest", Map.of("gatePassCount", 1), null);
        TraceStore.put("boosterMode.active", "EXTREMEZ");
        TraceStore.put("boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA"));
        TraceStore.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("retrievalOrder.lastSetBy", "PLAN_DSL");
        TraceStore.put("retrievalOrder.lastOrder", List.of("VECTOR", "KG", "WEB"));
        TraceStore.put("retrievalOrder.lastOrderSize", 3);
        TraceStore.put("retrievalOrder.authority.owner", "PLAN_DSL");
        TraceStore.put("retrievalOrder.authority.suppressedOwner", "MoE");
        TraceStore.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
        TraceStore.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
        TraceStore.put("retrieval.order.strategy.applied", false);
        TraceStore.put("retrieval.order.strategy.reason", "plan_dsl_authority");
        TraceStore.put("routing.executionPlan.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.triggers", List.of("lowRecall", "lowAuthority", "highRiskTail"));
        TraceStore.put("routing.executionPlan.extremeZ", true);
        TraceStore.put("routing.executionPlan.overdrive", false);
        TraceStore.put("routing.executionPlan.hypernova", false);
        TraceStore.put("routing.executionPlan.applied", true);
        TraceStore.put("routing.executionPlan.applied.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.applied.triggers", List.of("lowRecall", "highRiskTail"));
        TraceStore.put("routing.executionPlan.applied.stages", List.of("plan", "guard", "apply"));
        TraceStore.put("routing.executionPlan.applied.extremeZ", true);
        TraceStore.put("routing.executionPlan.applied.overdrive", false);
        TraceStore.put("routing.executionPlan.applied.hypernova", false);
        TraceStore.put("specialMode.conflict.suppressed", "OVERDRIVE,HYPERNOVA");
        TraceStore.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("hypernova.twpmP", 4.0d);
        TraceStore.put("hypernova.twpmP.max", 8.0d);
        TraceStore.put("hypernova.twpmP.maxBounded", true);
        TraceStore.put("hypernova.cvarFusedScore", 0.77d);
        TraceStore.put("hypernova.cvarAlpha", 0.25d);
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("hypernova.clampApplied", true);
        TraceStore.put("hypernova.dppApplied", true);
        TraceStore.put("hypernova.dppInputCount", 8);
        TraceStore.put("hypernova.dppOutputCount", 6);
        TraceStore.put("hypernova.dppDisabledReason", "");
        TraceStore.put("hypernova.sourceScoreScaleMismatchCount", 2);
        TraceStore.put("hypernova.sourceScoreScaleMismatchPolicy", "fallback_to_base_norm");
        TraceStore.put("nova.hypernova.riskK.used", true);
        TraceStore.put("nova.hypernova.riskK.candidateCount", 3);
        TraceStore.put("nova.hypernova.riskK.totalK", 12);
        TraceStore.put("nova.hypernova.riskK.alloc.sum", 12);
        TraceStore.put("hypernova.riskKAlloc", Map.of("used", true, "totalK", 12, "sum", 12));
        TraceStore.put("cihRag.mlaBreadcrumbCount", 2);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
        TraceStore.put("cihRag.iqrDisabledReason", "pass_through_stub_pending_iqr_impl");
        TraceStore.put("embed.matryoshka.slice.actual", 4096);
        TraceStore.put("embed.matryoshka.slice.target", 1536);
        TraceStore.put("embed.matryoshka.slice.reductionRatio", 0.625d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio", 0.375d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup", 2.6667d);
        TraceStore.put("embed.matryoshka.strategy", "MRL_PREFIX");
        TraceStore.put("localLlm.startup.status", "skipped");
        TraceStore.put("localLlm.startup.reason", "disabled_or_autostart_false");
        TraceStore.put("localLlm.startup.host", "user:secret@127.0.0.1:11435");
        TraceStore.put("localLlm.startup.hostHash", "hash:abcd1234");
        TraceStore.put("localLlm.warmup.status", "skipped");
        TraceStore.put("localLlm.warmup.modelHash", "hash:modelabcd");
        TraceStore.put("localLlm.warmup.modelLength", 14);
        TraceStore.put("localLlm.warmup.targetDim", 1536);
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.mode", "FAIL_FAST");
        TraceStore.put("llm.modelGuard.endpoint", "/v1/chat/completions");
        TraceStore.put("llm.modelGuard.failReason", "EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
        TraceStore.put("llm.modelGuard.requestedModelHash", "hash:modelguard");
        TraceStore.put("llm.modelGuard.requestedModelLength", 19);
        ReportSnapshotService service = service(store, new RawMatrixBuffer());

        Map<String, Object> kpi = service.traceKpi();

        assertEquals(2L, ((Number) kpi.get("agentReportEventCount")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Long> byProbe = (Map<String, Long>) kpi.get("agentReportEventsByProbe");
        assertEquals(1L, byProbe.get("AGENT_REPORT_CFVM"));
        assertEquals(1L, byProbe.get("AGENT_REPORT_GATES"));
        @SuppressWarnings("unchecked")
        Map<String, Object> boosterMode = (Map<String, Object>) kpi.get("boosterMode");
        assertEquals("EXTREMEZ", boosterMode.get("boosterMode.active"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), boosterMode.get("boosterMode.excludedModes"));
        assertEquals("EXTREMEZ_HYPERNOVA_OVERDRIVE", boosterMode.get("boosterMode.priority"));
        assertEquals("single_primary_mode:EXTREMEZ_HYPERNOVA_OVERDRIVE",
                boosterMode.get("boosterMode.exclusionReason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> retrievalOrder = (Map<String, Object>) kpi.get("retrievalOrder");
        assertEquals("PLAN_DSL", retrievalOrder.get("retrievalOrder.lastSetBy"));
        assertEquals(List.of("VECTOR", "KG", "WEB"), retrievalOrder.get("retrievalOrder.lastOrder"));
        assertEquals(3, retrievalOrder.get("retrievalOrder.lastOrderSize"));
        assertEquals("PLAN_DSL", retrievalOrder.get("retrievalOrder.authority.owner"));
        assertEquals("MoE", retrievalOrder.get("retrievalOrder.authority.suppressedOwner"));
        assertEquals("rulebreak_speed_first", retrievalOrder.get("retrievalOrder.authority.reason"));
        assertEquals("plan_dsl_preempts_strategy_selection",
                retrievalOrder.get("retrievalOrder.authority.suppressedReason"));
        assertEquals(false, retrievalOrder.get("retrieval.order.strategy.applied"));
        assertEquals("plan_dsl_authority", retrievalOrder.get("retrieval.order.strategy.reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> routingPlan = (Map<String, Object>) kpi.get("routingPlan");
        assertEquals("EXTREMEZ", routingPlan.get("routing.executionPlan.primaryMode"));
        assertEquals(List.of("lowRecall", "lowAuthority", "highRiskTail"),
                routingPlan.get("routing.executionPlan.triggers"));
        assertEquals(true, routingPlan.get("routing.executionPlan.extremeZ"));
        assertEquals(false, routingPlan.get("routing.executionPlan.overdrive"));
        assertEquals(false, routingPlan.get("routing.executionPlan.hypernova"));
        assertEquals(true, routingPlan.get("routing.executionPlan.applied"));
        assertEquals("EXTREMEZ", routingPlan.get("routing.executionPlan.applied.primaryMode"));
        assertEquals(List.of("lowRecall", "highRiskTail"),
                routingPlan.get("routing.executionPlan.applied.triggers"));
        assertEquals(List.of("plan", "guard", "apply"),
                routingPlan.get("routing.executionPlan.applied.stages"));
        assertEquals("OVERDRIVE_HYPERNOVA", routingPlan.get("specialMode.conflict.suppressed"));
        assertEquals("EXTREMEZ_HYPERNOVA_OVERDRIVE", routingPlan.get("specialMode.priority"));
        assertTrue(((Number) kpi.get("hypernovaKeyCount")).intValue() >= 15);
        @SuppressWarnings("unchecked")
        Map<String, Object> hypernova = (Map<String, Object>) kpi.get("hypernova");
        assertEquals(4.0d, hypernova.get("hypernova.twpmP"));
        assertEquals(8.0d, hypernova.get("hypernova.twpmP.max"));
        assertEquals(true, hypernova.get("hypernova.twpmP.maxBounded"));
        assertEquals(0.77d, hypernova.get("hypernova.cvarFusedScore"));
        assertEquals(0.25d, hypernova.get("hypernova.cvarAlpha"));
        assertEquals(0.618d, hypernova.get("hypernova.cvarPhi"));
        assertEquals(true, hypernova.get("hypernova.clampApplied"));
        assertEquals(true, hypernova.get("hypernova.dppApplied"));
        assertEquals(8, hypernova.get("hypernova.dppInputCount"));
        assertEquals(6, hypernova.get("hypernova.dppOutputCount"));
        assertEquals("", hypernova.get("hypernova.dppDisabledReason"));
        assertEquals(2, hypernova.get("hypernova.sourceScoreScaleMismatchCount"));
        assertEquals("fallback_to_base_norm", hypernova.get("hypernova.sourceScoreScaleMismatchPolicy"));
        assertEquals(true, hypernova.get("nova.hypernova.riskK.used"));
        assertEquals(12, hypernova.get("nova.hypernova.riskK.totalK"));
        assertEquals(12, hypernova.get("nova.hypernova.riskK.alloc.sum"));
        @SuppressWarnings("unchecked")
        Map<String, Object> riskKAlloc = (Map<String, Object>) hypernova.get("hypernova.riskKAlloc");
        assertEquals(true, riskKAlloc.get("used"));
        assertEquals(12, riskKAlloc.get("totalK"));
        assertEquals(12, riskKAlloc.get("sum"));
        assertTrue(((Number) kpi.get("cihRagKeyCount")).intValue() >= 3);
        assertTrue(((Number) kpi.get("matryoshkaKeyCount")).intValue() >= 4);
        assertTrue(((Number) kpi.get("localLlmKeyCount")).intValue() >= 7);
        @SuppressWarnings("unchecked")
        Map<String, Object> cihRag = (Map<String, Object>) kpi.get("cihRag");
        assertEquals(2, cihRag.get("cihRag.mlaBreadcrumbCount"));
        assertEquals(true, cihRag.get("cihRag.breadcrumb.queryRedacted"));
        assertEquals("pass_through_stub_pending_iqr_impl", cihRag.get("cihRag.iqrDisabledReason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> matryoshka = (Map<String, Object>) kpi.get("matryoshka");
        assertEquals(4096, matryoshka.get("embed.matryoshka.slice.actual"));
        assertEquals(1536, matryoshka.get("embed.matryoshka.slice.target"));
        assertEquals(0.625d, matryoshka.get("embed.matryoshka.slice.reductionRatio"));
        assertEquals(0.375d, matryoshka.get("embed.matryoshka.slice.expectedDistanceOpsRatio"));
        assertEquals(2.6667d, matryoshka.get("embed.matryoshka.slice.expectedDistanceOpsSpeedup"));
        assertEquals("MRL_PREFIX", matryoshka.get("embed.matryoshka.strategy"));
        @SuppressWarnings("unchecked")
        Map<String, Object> localLlm = (Map<String, Object>) kpi.get("localLlm");
        assertEquals("skipped", localLlm.get("localLlm.startup.status"));
        assertEquals("disabled_or_autostart_false", localLlm.get("localLlm.startup.reason"));
        assertEquals("hash:abcd1234", localLlm.get("localLlm.startup.hostHash"));
        assertEquals("hash:modelabcd", localLlm.get("localLlm.warmup.modelHash"));
        assertEquals(14, localLlm.get("localLlm.warmup.modelLength"));
        assertFalse(localLlm.containsKey("localLlm.startup.host"));
        @SuppressWarnings("unchecked")
        Map<String, Object> modelGuard = (Map<String, Object>) kpi.get("modelGuard");
        assertEquals(true, modelGuard.get("llm.modelGuard.triggered"));
        assertEquals("FAIL_FAST", modelGuard.get("llm.modelGuard.mode"));
        assertEquals("/v1/chat/completions", modelGuard.get("llm.modelGuard.endpoint"));
        assertEquals("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH", modelGuard.get("llm.modelGuard.failReason"));
        assertEquals("hash:modelguard", modelGuard.get("llm.modelGuard.requestedModelHash"));
        assertEquals(19, modelGuard.get("llm.modelGuard.requestedModelLength"));
        assertFalse(kpi.toString().contains("user:secret"));
    }

    @Test
    void extremezStatusReportsActiveBurstHandlerTraceInsteadOfLegacyStub() {
        ReportSnapshotService service = service(new DebugEventStore(), new RawMatrixBuffer());

        Map<String, Object> initial = service.extremezStatus();

        assertEquals(true, initial.get("stubMode"));
        assertEquals("legacy-explode", initial.get("runtimeMode"));
        assertEquals(0L, ((Number) initial.get("explodeCount")).longValue());
        assertEquals(false, initial.get("wired"));
        assertEquals("UNKNOWN", initial.get("primaryMode"));

        TraceStore.put("extremez.systemHandler.wired", true);
        TraceStore.put("extremez.systemHandler.plannerWired", true);
        TraceStore.put("extremez.systemHandler.webRetrieverWired", true);
        TraceStore.put("extremez.systemHandler.ragServiceWired", false);
        TraceStore.put("extremez.systemHandler.rrfWired", true);
        TraceStore.put("extremez.systemHandler.authorityScorerWired", true);
        TraceStore.put("extremez.systemHandler.plan.primaryMode", "EXTREMEZ");
        TraceStore.put("extremez.systemHandler.plan.reason", "lowRecall");
        TraceStore.put("extremez.trigger.activate", true);
        TraceStore.put("extremez.execute.activated", true);
        TraceStore.put("extremez.execute.subQueryCount", 2L);
        TraceStore.put("extremez.execute.rawCount", 4L);
        TraceStore.put("extremez.execute.refinedCount", 3L);
        TraceStore.put("extremeZ.cancelShieldWrapped", true);
        TraceStore.put("extremeZ.timeBudgetConsumedMs", 37L);
        Map<String, Object> activated = service.extremezStatus();

        assertEquals(false, activated.get("stubMode"));
        assertEquals("burst-handler", activated.get("runtimeMode"));
        assertEquals(true, activated.get("wired"));
        assertEquals(true, activated.get("plannerWired"));
        assertEquals(true, activated.get("webRetrieverWired"));
        assertEquals(false, activated.get("ragServiceWired"));
        assertEquals(true, activated.get("rrfWired"));
        assertEquals(true, activated.get("authorityScorerWired"));
        assertEquals("EXTREMEZ", activated.get("primaryMode"));
        assertEquals("lowRecall", activated.get("activationReason"));
        assertEquals(true, activated.get("triggerActivated"));
        assertEquals(true, activated.get("executeActivated"));
        assertEquals(2L, ((Number) activated.get("subQueryCount")).longValue());
        assertEquals(4L, ((Number) activated.get("rawCount")).longValue());
        assertEquals(3L, ((Number) activated.get("refinedCount")).longValue());
        assertEquals(true, activated.get("cancelShieldWrapped"));
        assertEquals(37L, ((Number) activated.get("timeBudgetConsumedMs")).longValue());
        assertEquals(0L, ((Number) activated.get("explodeCount")).longValue());
    }

    @Test
    void memoryReinforcementStatsFallsBackWhenServiceIsNotWired() {
        ReportSnapshotService service = service(new DebugEventStore(), new RawMatrixBuffer());

        Map<String, Object> stats = service.memoryReinforcement();

        assertEquals(false, stats.get("available"));
        assertEquals("memory_reinforcement_not_wired", stats.get("reason"));
    }

    private static ReportSnapshotService service(DebugEventStore store, RawMatrixBuffer buffer) {
        return new ReportSnapshotService(
                store,
                buffer,
                null,
                null,
                null,
                null,
                null,
                null,
                0.35d,
                24,
                2,
                true);
    }
}
