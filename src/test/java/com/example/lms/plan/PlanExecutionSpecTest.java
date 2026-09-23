package com.example.lms.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * plan.when / plan.pipeline execution semantics: tri-state evaluation,
 * unobserved metrics stay UNKNOWN (never coerced to zero), stage ledger maps
 * declared labels to real runtime evidence only.
 */
class PlanExecutionSpecTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    private static Map<String, Object> planNode(String planId) throws Exception {
        Map<String, Object> root = YAML.readValue(
                Files.readString(Path.of("main/resources/plans", planId + ".yaml")), Map.class);
        return (Map<String, Object>) root.get("plan");
    }

    private static PlanExecutionSpec.StageFlags flags(boolean expansionEligible) {
        return new PlanExecutionSpec.StageFlags(true, true, true, true, expansionEligible);
    }

    @Test
    void parsesBraveWhenAndPipelineWithoutDiagnostics() throws Exception {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(planNode("brave.v1"));
        assertTrue(spec.whenPresent());
        assertEquals(9, spec.pipeline().size());
        assertTrue(spec.diagnostics().isEmpty(), "authored plan parses cleanly: " + spec.diagnostics());
        assertTrue(spec.declaresExpansion());
    }

    @Test
    void literalTrueActivatesAndMissingMetricIsUnknownNotZero() throws Exception {
        PlanExecutionSpec safe = PlanExecutionSpec.parse(planNode("safe_autorun.v1"));
        assertEquals(PlanExecutionSpec.TriState.TRUE, safe.evaluateWhen(Map.<String, Object>of()).state());

        PlanExecutionSpec brave = PlanExecutionSpec.parse(planNode("brave.v1"));
        // header off + initial_recall unobserved -> UNKNOWN (never treated as 0 < 0.35)
        PlanExecutionSpec.WhenVerdict verdict = brave.evaluateWhen(Map.<String, Object>of(
                "request.header.x-brave-mode", "off"));
        assertEquals(PlanExecutionSpec.TriState.UNKNOWN, verdict.state());
        PlanExecutionSpec.ConditionEval metric = verdict.conditions().stream()
                .filter(c -> c.expression().contains("initial_recall")).findFirst().orElseThrow();
        assertEquals("unknown", metric.result());
        assertEquals("unobserved:metric", metric.detail());
    }

    @Test
    void observedMetricResolvesNumericCompare() throws Exception {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(Map.<String, Object>of(
                "when", Map.<String, Object>of("any", List.of("metrics.result_count < 3")),
                "pipeline", List.of("retrieve.dynamicChain")));
        assertEquals(PlanExecutionSpec.TriState.TRUE,
                spec.evaluateWhen(Map.<String, Object>of("metrics.result_count", 0)).state());
        assertEquals(PlanExecutionSpec.TriState.FALSE,
                spec.evaluateWhen(Map.<String, Object>of("metrics.result_count", 9)).state());
    }

    @Test
    void allResolvedFalseYieldsFalseAndSuppressesExpansion() {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(Map.<String, Object>of(
                "when", Map.<String, Object>of("any", List.of("request.header.X-Brave-Mode == \"on\"")),
                "pipeline", List.of("analyze.selfAsk", "retrieve.dynamicChain")));
        PlanExecutionSpec.WhenVerdict verdict = spec.evaluateWhen(Map.<String, Object>of(
                "request.header.x-brave-mode", "off"));
        assertEquals(PlanExecutionSpec.TriState.FALSE, verdict.state());
        var ledger = spec.stageLedger(Map.<String, Object>of("stage.web", "success:5"), flags(false));
        assertEquals(PlanExecutionSpec.StageStatus.SKIPPED_WHEN_INACTIVE, ledger.get(0).status());
        assertEquals(PlanExecutionSpec.StageStatus.EXECUTED, ledger.get(1).status());
    }

    @Test
    void malformedConditionAndUnsupportedStageAreDiagnosticsNotExecution() {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(Map.<String, Object>of(
                "when", Map.<String, Object>of("any", List.of("system.exec('rm -rf /')", "metrics.result_count >= 1")),
                "pipeline", List.of("retrieve.dynamicChain", "bogus stage", "retrieve.dynamicChain", "nosuch.stage")));
        assertTrue(spec.diagnostics().stream().anyMatch(d -> d.startsWith("unsupported_condition:")));
        assertTrue(spec.diagnostics().stream().anyMatch(d -> d.startsWith("malformed_stage:")));
        assertTrue(spec.diagnostics().stream().anyMatch(d -> d.startsWith("duplicate_stage:")));
        assertEquals(PlanExecutionSpec.TriState.TRUE,
                spec.evaluateWhen(Map.<String, Object>of("metrics.result_count", 5)).state());

        var ledger = spec.stageLedger(Map.<String, Object>of("stage.web", "success:3"), flags(true));
        // malformed "bogus stage" is filtered at parse time: the executable list is
        // [retrieve.dynamicChain, retrieve.dynamicChain, nosuch.stage]
        assertEquals(PlanExecutionSpec.StageStatus.EXECUTED, ledger.get(0).status());
        assertEquals(PlanExecutionSpec.StageStatus.SKIPPED_DUPLICATE, ledger.get(1).status());
        assertEquals(PlanExecutionSpec.StageStatus.UNAVAILABLE, ledger.get(2).status());
        assertEquals("no_binding", ledger.get(2).detail());
    }

    @Test
    void ledgerNeverClaimsExecutionWithoutMarkers() {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(Map.<String, Object>of(
                "pipeline", List.of("analyze.selfAsk", "retrieve.dynamicChain", "fuse.rrf.weighted",
                        "rerank.biEncoder", "rerank.crossEncoder.onnx", "diversity.dpp",
                        "prompt.build", "answer.generate")));
        var ledger = spec.stageLedger(Map.<String, Object>of(), flags(true));
        Map<String, PlanExecutionSpec.StageStatus> byStage = new LinkedHashMap<>();
        ledger.forEach(e -> byStage.put(e.stage(), e.status()));
        // flag on but no planner marker -> honestly declared-only, never executed
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("analyze.selfAsk"));
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("retrieve.dynamicChain"));
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("fuse.rrf.weighted"));
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("rerank.biEncoder"));
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("rerank.crossEncoder.onnx"));
        assertEquals(PlanExecutionSpec.StageStatus.DECLARED, byStage.get("diversity.dpp"));
        assertEquals(PlanExecutionSpec.StageStatus.DELEGATED, byStage.get("prompt.build"));
        assertEquals(PlanExecutionSpec.StageStatus.DELEGATED, byStage.get("answer.generate"));

        var executed = spec.stageLedger(Map.<String, Object>of(
                "stage.vector", "success:4",
                "stage.fuse", 6,
                "stage.biencoder", 5,
                "stage.onnx", "skipped:missing_dependency",
                "stage.dpp", "error: boom"), flags(true));
        byStage.clear();
        executed.forEach(e -> byStage.put(e.stage(), e.status()));
        assertEquals(PlanExecutionSpec.StageStatus.EXECUTED, byStage.get("retrieve.dynamicChain"));
        assertEquals(PlanExecutionSpec.StageStatus.EXECUTED, byStage.get("fuse.rrf.weighted"));
        assertEquals(PlanExecutionSpec.StageStatus.EXECUTED, byStage.get("rerank.biEncoder"));
        assertEquals(PlanExecutionSpec.StageStatus.SKIPPED_DEPENDENCY, byStage.get("rerank.crossEncoder.onnx"));
        assertEquals(PlanExecutionSpec.StageStatus.FAILED, byStage.get("diversity.dpp"));
    }

    @Test
    void flagOffStagesReportSkippedFlagOff() {
        PlanExecutionSpec spec = PlanExecutionSpec.parse(Map.<String, Object>of(
                "pipeline", List.of("analyze.selfAsk", "rerank.biEncoder",
                        "rerank.crossEncoder.onnx", "diversity.dpp")));
        var ledger = spec.stageLedger(Map.<String, Object>of(),
                new PlanExecutionSpec.StageFlags(false, false, false, false, true));
        assertTrue(ledger.stream().allMatch(e -> e.status() == PlanExecutionSpec.StageStatus.SKIPPED_FLAG_OFF));
    }

    @Test
    void emptySpecIsHonest() {
        PlanExecutionSpec spec = PlanExecutionSpec.empty("missing_resource");
        assertTrue(spec.isEmpty());
        assertEquals("missing_resource", spec.emptyReason());
        assertEquals(PlanExecutionSpec.TriState.TRUE, spec.evaluateWhen(Map.<String, Object>of()).state());
        assertTrue(spec.stageLedger(Map.<String, Object>of(), flags(true)).isEmpty());
    }
}
