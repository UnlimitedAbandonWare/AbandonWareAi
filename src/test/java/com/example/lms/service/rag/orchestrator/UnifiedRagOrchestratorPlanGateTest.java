package com.example.lms.service.rag.orchestrator;

import com.example.lms.nova.NovaRequestContext;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan.when / plan.pipeline wiring: a conclusively FALSE when-gate suppresses
 * plan-driven expansion while preserving caller intent; UNKNOWN never
 * fabricates metrics; the stage ledger reports only real runtime evidence.
 */
class UnifiedRagOrchestratorPlanGateTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        NovaRequestContext.setBrave(false);
    }

    private static final String GATE_FALSE_PLAN = """
            plan:
              id: when_gate_false.v1
              when:
                any: [ false ]
              overrides:
                knobs:
                  expand.selfAsk.count: 3
              pipeline:
                - analyze.selfAsk
                - retrieve.dynamicChain
            """;

    private static final String METRIC_PLAN = """
            plan:
              id: when_metric.v1
              when:
                any:
                  - metrics.initial_recall < 0.35
              overrides:
                knobs:
                  expand.selfAsk.count: 3
              pipeline:
                - analyze.selfAsk
                - retrieve.dynamicChain
            """;

    private static PlanHintApplier applier(String planId, String yaml) {
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        return new PlanHintApplier(new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                    return new ByteArrayResource(bytes) {
                        @Override public String getFilename() { return planId + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        });
    }

    private static ApplicationContextRunner registerCommonBeans(ApplicationContextRunner runner) {
        return runner.withBean("analyzeWebSearchRetriever", ContentRetriever.class,
                        () -> query -> List.of(Content.from("web evidence 1"), Content.from("web evidence 2")))
                .withBean("vectorRetriever", ContentRetriever.class,
                        () -> query -> List.of(Content.from("vector evidence 1")))
                .withBean(com.example.lms.service.rag.LangChainRAGService.class,
                        () -> UnifiedRagOrchestratorPlanHintsTest.leafReturning(
                                query -> List.of(Content.from("vector evidence 1"))))
                .withBean(UnifiedRagOrchestrator.class);
    }

    private static UnifiedRagOrchestrator.QueryRequest baseRequest(String planId) {
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "plan gate verification";
        request.planId = planId;
        request.useWeb = true;
        request.useVector = true;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        request.topK = 8;
        return request;
    }

    @Test
    void falseWhenGateSuppressesPlanDrivenExpansion() {
        registerCommonBeans(new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier("when_gate_false.v1", GATE_FALSE_PLAN)))
                .run(context -> {
                    var request = baseRequest("when_gate_false.v1");
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertAll(
                            () -> assertEquals("false", response.debug.get("plan.when")),
                            () -> assertEquals("when_false", response.debug.get("plan.selfAsk.gated")),
                            () -> assertFalse(request.enableSelfAsk, "plan-driven expansion suppressed"),
                            () -> assertEquals(false, response.debug.get("plan.selfAsk.enabled")));
                });
    }

    @Test
    void falseWhenGatePreservesCallerSetFlag() {
        registerCommonBeans(new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier("when_gate_false.v1", GATE_FALSE_PLAN)))
                .run(context -> {
                    var request = baseRequest("when_gate_false.v1");
                    request.enableSelfAsk = true; // caller intent wins over a false gate
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertAll(
                            () -> assertEquals("false", response.debug.get("plan.when")),
                            () -> assertNull(response.debug.get("plan.selfAsk.gated"),
                                    "caller-set flag is never gated"),
                            () -> assertTrue(request.enableSelfAsk));
                });
    }

    @Test
    void unobservedMetricStaysUnknownAndDoesNotSuppress() {
        registerCommonBeans(new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> applier("when_metric.v1", METRIC_PLAN)))
                .run(context -> {
                    var request = baseRequest("when_metric.v1");
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertAll(
                            () -> assertEquals("unknown", response.debug.get("plan.when")),
                            () -> assertEquals("unknown", response.debug.get("plan.when.post"),
                                    "metrics.initial_recall has no producer; post scope stays unknown"),
                            () -> assertNull(response.debug.get("plan.selfAsk.gated")),
                            () -> assertTrue(request.enableSelfAsk, "unknown gate preserves plan-driven expansion"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> conditions =
                            (List<Map<String, Object>>) response.debug.get("plan.when.conditions");
                    assertEquals("unknown", conditions.get(0).get("result"));
                    assertEquals("unobserved:metric", conditions.get(0).get("detail"));
                });
    }

    @Test
    void bravePlanHeaderOffStaysUnknownNotFalse() {
        registerCommonBeans(new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader())))
                .run(context -> {
                    var request = baseRequest("brave.v1");
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    assertEquals("unknown", response.debug.get("plan.when"),
                            "header off + unobserved metric must stay unknown");
                    assertTrue(request.enableSelfAsk, "existing brave.v1 contract: plan knob enables selfAsk");
                });
    }

    @Test
    void braveHeaderOnResolvesWhenTrue() {
        boolean previous = NovaRequestContext.isBrave();
        try {
            NovaRequestContext.setBrave(true);
            registerCommonBeans(new ApplicationContextRunner()
                    .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader())))
                    .run(context -> {
                        var request = baseRequest("brave.v1");
                        var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                        assertEquals("true", response.debug.get("plan.when"));
                    });
        } finally {
            NovaRequestContext.setBrave(previous);
        }
    }

    @Test
    void stageLedgerReportsRealEvidenceOnly() {
        registerCommonBeans(new ApplicationContextRunner()
                .withBean(PlanHintApplier.class, () -> new PlanHintApplier(new DefaultResourceLoader())))
                .run(context -> {
                    var request = baseRequest("brave.v1");
                    var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ledger =
                            (List<Map<String, Object>>) response.debug.get("plan.stageLedger");
                    assertNotNull(ledger, "declared pipeline produces an execution ledger");
                    Map<String, String> statusByStage = new java.util.LinkedHashMap<>();
                    Map<String, String> detailByStage = new java.util.LinkedHashMap<>();
                    for (Map<String, Object> entry : ledger) {
                        statusByStage.put(String.valueOf(entry.get("stage")),
                                String.valueOf(entry.get("status")));
                        Object detail = entry.get("detail");
                        if (detail != null) {
                            detailByStage.put(String.valueOf(entry.get("stage")), String.valueOf(detail));
                        }
                    }
                    assertAll(
                            () -> assertEquals("unavailable", statusByStage.get("analyze.selfAsk"),
                                    "planner bean absent -> honestly unavailable"),
                            () -> assertEquals("planner_absent", detailByStage.get("analyze.selfAsk")),
                            () -> assertEquals("executed", statusByStage.get("retrieve.dynamicChain"),
                                    "real stage.web marker observed"),
                            () -> assertEquals("unavailable", statusByStage.get("expand.queryBurst"),
                                    "no runtime binding -> never claimed executed"),
                            () -> assertEquals("unavailable", statusByStage.get("narrow.overdrive.anchor")),
                            () -> assertEquals("executed", statusByStage.get("fuse.rrf.weighted"),
                                    "fusion ran with a real marker"),
                            () -> assertEquals("delegated", statusByStage.get("prompt.build")),
                            () -> assertEquals("delegated", statusByStage.get("answer.generate")));
                    for (Map<String, Object> entry : ledger) {
                        if ("executed".equals(entry.get("status")) || "enabled".equals(entry.get("status"))) {
                            assertNotNull(entry.get("evidence"),
                                    "executed/enabled always names its evidence marker: " + entry.get("stage"));
                        }
                    }
                });
    }
}
