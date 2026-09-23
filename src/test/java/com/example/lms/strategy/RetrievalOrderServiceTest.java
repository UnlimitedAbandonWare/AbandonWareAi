package com.example.lms.strategy;

import ai.abandonware.nova.config.NovaFailurePatternProperties;
import ai.abandonware.nova.orch.failpattern.FailurePatternCooldownRegistry;
import ai.abandonware.nova.orch.failpattern.FailurePatternDetector;
import ai.abandonware.nova.orch.failpattern.FailurePatternJsonlWriter;
import ai.abandonware.nova.orch.failpattern.FailurePatternMetrics;
import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.agent.context.AgentDbContextProvider;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakContextHolder;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.infra.selection.SelectionReplaySpec;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalOrderServiceTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        RuleBreakContextHolder.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        RuleBreakContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void fixedModeDoesNotConsumeReplayEntropy() {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forReplay();
        GuardContext context = new GuardContext();
        context.attachSelectionEntropy(
                SelectionEntropyFactory.replay(SelectionReplaySpec.v1(new byte[32])),
                ledger);
        GuardContextHolder.set(context);

        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "fixed");

        List<RetrievalOrderService.Source> order = service.decideOrder("fixed retrieval");

        assertEquals(List.of(
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG), order);
        assertEquals(0, ledger.snapshot(true).decisionCount());
        assertEquals(0, ledger.snapshot(true).drawCount());
    }

    @Test
    void highQuarantineRatioAddsTraceWarningWithoutChangingFixedOrder() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "fixed");
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        AgentDbContextProvider.MemorySnapshot memory = new AgentDbContextProvider.MemorySnapshot();
        memory.statusCounts.put("ACTIVE", 10L);
        memory.statusCounts.put("QUARANTINED", 4L);
        when(provider.memorySnapshot()).thenReturn(memory);
        ReflectionTestUtils.setField(service, "agentDbContextProvider", provider);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG), order);
        assertEquals("DEFAULT", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.vectorDeprioritized"));
        assertEquals(0.4d, (Double) TraceStore.get("retrieval.order.vectorQuarantineRatio"), 0.0001d);
    }

    @Test
    void dbContextWarningProbeIsFailSoft() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "fixed");
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.memorySnapshot()).thenThrow(
                new DataAccessResourceFailureException("db unavailable ownerToken=redacted-test-token"));
        ReflectionTestUtils.setField(service, "agentDbContextProvider", provider);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG), order);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.dbContext.failSoft"));
        assertEquals("db_context_probe_failed", TraceStore.get("retrieval.order.dbContext.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("DataAccessResourceFailureException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("redacted-test-token"));
    }

    @Test
    void dynamicModeAppliesLearnedVectorFirstStrategyBeforeHeuristicFallback() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");

        boolean hasStrategySelector = java.util.Arrays.stream(RetrievalOrderService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(StrategySelectorService.class));
        assertTrue(hasStrategySelector, "RetrievalOrderService must consult StrategySelectorService in dynamic mode");

        StrategySelectorService selector = mock(StrategySelectorService.class);
        when(selector.selectForQuestion("RAG evidence starvation debug", null))
                .thenReturn(StrategySelectorService.Strategy.VECTOR_FIRST);
        ReflectionTestUtils.setField(service, "strategySelectorService", selector);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.KG), order);
        assertEquals("MoE", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(List.of("VECTOR", "WEB", "KG"), TraceStore.get("retrievalOrder.lastOrder"));
        assertEquals(3, TraceStore.get("retrievalOrder.lastOrderSize"));
        assertEquals("VECTOR_FIRST", TraceStore.get("retrieval.order.strategy.selected"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.strategy.applied"));
    }

    @Test
    void planDslAuthorityWinsBeforeMoeStrategySelection() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");
        RuleBreakContextHolder.set(RuleBreakContext.active(
                RuleBreakPolicy.SPEED_FIRST,
                "token-hash",
                Instant.now().plusSeconds(60),
                "rid-001",
                "sid-001"));
        StrategySelectorService selector = mock(StrategySelectorService.class);
        ReflectionTestUtils.setField(service, "strategySelectorService", selector);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG,
                RetrievalOrderService.Source.WEB), order);
        assertEquals("PLAN_DSL", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(List.of("VECTOR", "KG", "WEB"), TraceStore.get("retrievalOrder.lastOrder"));
        assertEquals(3, TraceStore.get("retrievalOrder.lastOrderSize"));
        assertEquals("PLAN_DSL", TraceStore.get("retrievalOrder.authority.owner"));
        assertEquals("MoE", TraceStore.get("retrievalOrder.authority.suppressedOwner"));
        assertEquals("rulebreak_speed_first", TraceStore.get("retrievalOrder.authority.reason"));
        assertEquals("plan_dsl_preempts_strategy_selection",
                TraceStore.get("retrievalOrder.authority.suppressedReason"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("retrieval.order.strategy.applied")));
        verifyNoInteractions(selector);
    }

    @Test
    void dynamicModeStrategyFailurePublishesStableReasonOnly() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");

        StrategySelectorService selector = mock(StrategySelectorService.class);
        when(selector.selectForQuestion("RAG evidence starvation debug", null))
                .thenThrow(new IllegalStateException("strategy failed ownerToken=redacted-test-token"));
        ReflectionTestUtils.setField(service, "strategySelectorService", selector);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG), order);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.strategy.failSoft"));
        assertEquals("strategy_selector_failed", TraceStore.get("retrieval.order.strategy.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("IllegalStateException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("redacted-test-token"));
    }

    @Test
    void dynamicModeUsesSearchRecoveryTraceToDemoteFailingWebSource() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");
        TraceStore.put("failpattern.searchRecovery.source", "web");
        TraceStore.put("failpattern.searchRecovery.reason", "zero_result");

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG,
                RetrievalOrderService.Source.WEB), order);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.failpattern.applied"));
        assertEquals("web", TraceStore.get("retrieval.order.failpattern.source"));
        assertEquals("CFVM_FAILURE_PATTERN", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("[VECTOR, KG, WEB]", TraceStore.get("cfvm.recoveryPath"));
    }

    @Test
    void dynamicModeFailurePatternCooldownUsesExplicitCfvmFailurePatternOwner() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");
        ReflectionTestUtils.setField(service, "failurePatterns", failurePatternCoolingDown("web"));

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG,
                RetrievalOrderService.Source.WEB), order);
        assertEquals("CFVM_FAILURE_PATTERN", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.failpattern.cooldown.applied"));
        assertEquals("web", TraceStore.get("retrieval.order.failpattern.cooldown.source"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
    }

    @Test
    void dynamicModeWithoutFailurePatternBeanTracesOptionalAbsenceBeforeLearnedStrategy() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");
        StrategySelectorService selector = mock(StrategySelectorService.class);
        when(selector.selectForQuestion("RAG evidence starvation debug", null))
                .thenReturn(StrategySelectorService.Strategy.VECTOR_FIRST);
        ReflectionTestUtils.setField(service, "strategySelectorService", selector);

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.KG), order);
        assertEquals("MoE", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.FALSE, TraceStore.get("retrieval.order.failurePatterns.available"));
        assertEquals("failure_patterns_absent", TraceStore.get("retrieval.order.failurePatterns.reason"));
    }

    @Test
    void dynamicModeTreatsTavilySearchRecoveryAsFailingWebSource() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "dynamic");
        TraceStore.put("failpattern.searchRecovery.source", "tavily");
        TraceStore.put("failpattern.searchRecovery.reason", "after_filter_starvation");

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG,
                RetrievalOrderService.Source.WEB), order);
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.failpattern.applied"));
        assertEquals("tavily", TraceStore.get("retrieval.order.failpattern.source"));
        assertEquals("after_filter_starvation", TraceStore.get("retrieval.order.failpattern.reason"));
        assertEquals("CFVM_FAILURE_PATTERN", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals("[VECTOR, KG, WEB]", TraceStore.get("cfvm.recoveryPath"));
    }

    @Test
    void cfvmDominantTilePublishesRetrievalOrderAdjustment() {
        RetrievalOrderService service = new RetrievalOrderService();
        double[] weights = new double[]{0.02d, 0.03d, 0.04d, 0.05d, 0.06d, 0.07d, 0.72d, 0.01d, 0.0d};

        boolean adjusted = service.adjustFromCfvm(6, weights);

        assertTrue(adjusted);
        assertEquals("CFVM", TraceStore.get("retrievalOrder.lastSetBy"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("[VECTOR, KG, WEB]", TraceStore.get("cfvm.recoveryPath"));
        assertEquals("", TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrievalOrder.cfvmSnapshotFound"));
        assertEquals(6, TraceStore.get("retrievalOrder.activeTile"));
        assertEquals("0.7200", TraceStore.get("retrievalOrder.cfvmWeight"));
        assertEquals(6, TraceStore.get("retrievalOrder.cfvm.activeTile"));
        assertEquals(6, TraceStore.get("retrievalOrder.cfvm.dominantSlot"));
    }

    @Test
    void cfvmMissingWeightsPublishesSnapshotAbsentAlias() {
        RetrievalOrderService service = new RetrievalOrderService();

        boolean adjusted = service.adjustFromCfvm(3, null);

        assertFalse(adjusted);
        assertEquals(Boolean.FALSE, TraceStore.get("retrievalOrder.cfvmSnapshotFound"));
        assertEquals("cfvm_weights_unavailable", TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals("[]", TraceStore.get("cfvm.recoveryPath"));
    }

    @Test
    void multipleOrderOwnersPublishConflictWinnerAndSetCount() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "fixed");
        double[] weights = new double[]{0.02d, 0.03d, 0.04d, 0.05d, 0.06d, 0.07d, 0.72d, 0.01d, 0.0d};

        service.decideOrder("RAG evidence starvation debug");
        boolean adjusted = service.adjustFromCfvm(6, weights);

        assertTrue(adjusted);
        assertEquals(2, TraceStore.get("retrievalOrder.setCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrievalOrder.conflictDetected"));
        assertEquals("CFVM", TraceStore.get("retrievalOrder.conflictWinner"));
        assertEquals("CFVM", TraceStore.get("retrievalOrder.lastSetBy"));
    }

    @Test
    void malformedSetCountPublishesInvalidNumberBreadcrumbWithoutRawValue() {
        RetrievalOrderService service = new RetrievalOrderService();
        ReflectionTestUtils.setField(service, "mode", "fixed");
        TraceStore.put("retrievalOrder.setCount", "not-a-number ownerToken=redacted-test-token");

        List<RetrievalOrderService.Source> order = service.decideOrder("RAG evidence starvation debug");

        assertEquals(List.of(
                RetrievalOrderService.Source.WEB,
                RetrievalOrderService.Source.VECTOR,
                RetrievalOrderService.Source.KG), order);
        assertEquals(1, TraceStore.get("retrievalOrder.setCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.order.suppressed.traceInt"));
        assertEquals("retrievalOrder.setCount", TraceStore.get("retrieval.order.suppressed.traceInt.key"));
        assertEquals("invalid_number", TraceStore.get("retrieval.order.suppressed.traceInt.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("redacted-test-token"));
    }

    @Test
    void cfvmWeakDominantWeightLeavesRetrievalOrderUnchangedWithReason() {
        RetrievalOrderService service = new RetrievalOrderService();
        double[] weights = new double[]{0.12d, 0.13d, 0.14d, 0.15d, 0.16d, 0.17d, 0.18d, 0.19d, 0.20d};

        boolean adjusted = service.adjustFromCfvm(6, weights);

        assertFalse(adjusted);
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.retrievalOrderAdjusted"));
        assertEquals("cfvm_weight_below_threshold", TraceStore.get("cfvm.retrievalOrderDisabledReason"));
        assertEquals("[]", TraceStore.get("cfvm.recoveryPath"));
    }

    private static FailurePatternOrchestrator failurePatternCoolingDown(String source) {
        NovaFailurePatternProperties props = new NovaFailurePatternProperties();
        props.getJsonl().setReadEnabled(false);
        props.getJsonl().setWriteEnabled(false);
        FailurePatternCooldownRegistry cooldown = new FailurePatternCooldownRegistry();
        FailurePatternOrchestrator orchestrator = new FailurePatternOrchestrator(
                new FailurePatternDetector(),
                new FailurePatternMetrics(null, props),
                new FailurePatternJsonlWriter(new ObjectMapper(), props),
                cooldown,
                new ObjectMapper(),
                props);
        cooldown.recordAt(source, System.currentTimeMillis(), 60_000L);
        return orchestrator;
    }
}
