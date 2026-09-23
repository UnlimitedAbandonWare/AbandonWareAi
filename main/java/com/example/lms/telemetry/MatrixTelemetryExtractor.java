package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;

import java.util.*;

/**
 * MatrixTelemetryExtractor
 * - Extracts M1..M9 observability vector/matrix axes from a generic run summary map.
 * - Pure Java (no external deps). Fail-soft: never throws upstream.
 * - All metrics are normalized into [0,1] and missing keys are zero-filled.
 *
 * Expected input (best-effort): Map<String, Object> runSummary
 *  keys may include (examples, optional):
 *    "source.web.count", "source.vector.count", "source.kg.count",
 *    "authority.avg", "novelty.avg", "loss.contribution", "reranker.cost",
 *    "latency.ms", "contradiction.score", "risk.score", "budget.usage"
 *
 * Output snapshot keys (fixed schema):
 *    m1_source_mix_web, m1_source_mix_vector, m1_source_mix_kg
 *    m2_authority, m3_novelty, m4_contradiction, m5_rerank_cost
 *    m6_loss_contribution (diagnostic sidecar scalar, not in _order)
 *    m8_latency, m9_budget, m7_risk  (numbered to match doc order loosely)
 *    q_anchor_drop_pressure, q_anchor_lane_pressure, q_route_correction_need
 *      (Trace Anchor projection signals from ablation.traceAnchor.*)
 */
public class MatrixTelemetryExtractor {

    public static final String SCHEMA_VERSION = "rag-matrix-v2";

    private static final List<String> VECTOR_ORDER = Collections.unmodifiableList(Arrays.asList(
            "m1_source_mix_web", "m1_source_mix_vector", "m1_source_mix_kg",
            "m2_authority", "m3_novelty", "m4_contradiction", "m5_rerank_cost",
            "m7_risk", "m8_latency", "m9_budget"
    ));

    private static double nz(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) {
            double numeric = ((Number) v).doubleValue();
            if (Double.isFinite(numeric)) {
                return numeric;
            }
            traceNumberParseSuppressed();
            return def;
        }
        if (v instanceof Boolean) return (Boolean) v ? 1.0 : 0.0;
        if (v instanceof Iterable<?>) {
            int count = 0;
            for (Object ignored : (Iterable<?>) v) count++;
            return count;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(v));
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceNumberParseSuppressed();
            return def;
        } catch (NumberFormatException ignore) {
            traceNumberParseSuppressed();
            return def;
        }
    }

    private static void traceNumberParseSuppressed() {
        TraceStore.put("telemetry.matrix.suppressed.stage", "numberParse");
        TraceStore.put("telemetry.matrix.suppressed.errorType", "invalid_number");
        TraceStore.put("telemetry.matrix.suppressed.numberParse", true);
        TraceStore.put("telemetry.matrix.suppressed.numberParse.errorType", "invalid_number");
    }

    private static double clip01(double x) {
        if (!Double.isFinite(x)) return 0;
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    public Map<String, Object> extract(Map<String, Object> runSummary) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String,Object> m = (runSummary == null) ? new HashMap<>() : runSummary;
            out.put("matrix.schemaVersion", SCHEMA_VERSION);

            // Source mix (normalize counts if provided)
            double web = nz(m.get("source.web.count"), 0);
            double vec = nz(m.get("source.vector.count"), 0);
            double kg  = nz(m.get("source.kg.count"), 0);
            double sum = Math.max(1.0, web + vec + kg);
            out.put("m1_source_mix_web", clip01(web / sum));
            out.put("m1_source_mix_vector", clip01(vec / sum));
            out.put("m1_source_mix_kg", clip01(kg / sum));

            // Authority [0..1]
            out.put("m2_authority", clip01(nz(m.get("authority.avg"), 0)));

            // Novelty [0..1] (1-highly novel)
            out.put("m3_novelty", clip01(nz(m.get("novelty.avg"), 0)));

            // Contradiction [0..1]
            out.put("m4_contradiction", clip01(nz(m.get("contradiction.score"), 0)));

            // Reranker cost normalized (if raw ms or tokens, apply log-normalization)
            double rrCost = nz(m.get("reranker.cost"), 0);
            double rrNorm = rrCost <= 0 ? 0 : Math.min(1.0, Math.log10(1 + rrCost) / 3.0);
            out.put("m5_rerank_cost", rrNorm);

            // Loss contribution is a diagnostic sidecar. Keep it outside _order so
            // virtual-point vectors and historical 10-slot contracts stay stable.
            out.put("m6_loss_contribution", maxDouble(m,
                    "m6_loss_contribution",
                    "loss.contribution",
                    "ablation.loss.contribution",
                    "ablation.expectedDelta.max"));

            // Risk [0..1]
            out.put("m7_risk", clip01(nz(m.get("risk.score"), 0)));

            // Latency [ms] -> normalized via soft saturation around 3s
            double latMs = nz(m.get("latency.ms"), 0);
            double latNorm = Math.tanh(latMs / 3000.0);
            out.put("m8_latency", clip01(latNorm));

            // Budget usage [0..1]
            out.put("m9_budget", clip01(nz(m.get("budget.usage"), 0)));

            double qRetrievalHealth = maxDouble(m,
                    "q_retrieval_health",
                    "rag.eval.normalized.balancedScore",
                    "retrieval.health",
                    "balancedScore");
            double qFailsoftPressure = maxDouble(m,
                    "q_failsoft_pressure",
                    "failsoft.pressure",
                    "provider.disabled.pressure",
                    "after.filter.starvation.pressure");
            double qGpuGatewayPressure = maxDouble(m,
                    "q_gpu_gateway_pressure",
                    "gpu.gateway.pressure",
                    "gpu.gateway.unreachable.pressure");
            qGpuGatewayPressure = Math.max(qGpuGatewayPressure, max(
                    nz(m.get("uaw.gpu-gateway.admission.blocked"), 0),
                    clip01(nz(m.get("uaw.gpu-gateway.admission.blocked.count"), 0)),
                    clip01(nz(m.get("gpu.gateway.unreachable.count"), 0))));
            double qGpuHardwarePressure = maxDouble(m,
                    "q_gpu_hardware_pressure",
                    "gpu.hardware.pressure",
                    "uaw.gpu-hardware.admission.pressure");
            qGpuHardwarePressure = Math.max(qGpuHardwarePressure, gpuHardwareAdmissionPressure(m));
            double qKgDegradationPressure = maxDouble(m,
                    "q_kg_degradation_pressure",
                    "kg.degradation.pressure",
                    "rag.eval.kgAxis.degradationPressure");
            qKgDegradationPressure = Math.max(qKgDegradationPressure, kgNeo4jDegradationPressure(m));
            double qGraphDependencyPressure = maxDouble(m,
                    "q_graph_dependency_pressure",
                    "graph.dependency.pressure",
                    "retrieval.dependency.kg.pressure");
            qGraphDependencyPressure = Math.max(qGraphDependencyPressure, qKgDegradationPressure);
            double qLlmUpstreamPressure = maxDouble(m,
                    "q_llm_upstream_pressure",
                    "llm.upstream.pressure");
            qLlmUpstreamPressure = Math.max(qLlmUpstreamPressure,
                    clip01(nz(m.get("llm.retry.budget_exhausted.count"), 0)));
            qFailsoftPressure = Math.max(qFailsoftPressure, clip01(
                    (nz(m.get("provider.disabled.count"), 0)
                            + nz(m.get("zero.result.count"), 0)
                            + nz(m.get("after.filter.starvation.count"), 0)
                            + nz(m.get("timeout.count"), 0)
                            + nz(m.get("cancelled.count"), 0)
                            + nz(m.get("rate.limit.count"), 0)
                            + nz(m.get("llm.retry.budget_exhausted.count"), 0)
                            + nz(m.get("overdrive.failsoft.count"), 0)) / 6.0));
            qFailsoftPressure = Math.max(qFailsoftPressure, qGpuGatewayPressure);
            qFailsoftPressure = Math.max(qFailsoftPressure, qGpuHardwarePressure);
            qFailsoftPressure = Math.max(qFailsoftPressure, qGraphDependencyPressure);
            qFailsoftPressure = Math.max(qFailsoftPressure, qLlmUpstreamPressure);

            double gateFailures = nz(m.get("gate.failure.count"), 0)
                    + nz(m.get("citation.gate.failed"), 0)
                    + nz(m.get("gate.final.failed"), 0)
                    + nz(m.get("finalSigmoidGate.failed"), 0);
            double qGateQuality = maxDouble(m, "q_gate_quality", "gate.quality");
            if (qGateQuality <= 0.0 && hasAny(m,
                    "gate.failure.count", "citation.gate.failed", "gate.final.failed", "finalSigmoidGate.failed")) {
                qGateQuality = 1.0 - clip01(gateFailures / 3.0);
            }

            double qStageDrop = maxDouble(m, "q_stage_drop", "stage.drop.max", "rag.eval.stageDrop.max");
            double qStageSourceCollapse = maxDouble(m,
                    "q_stage_source_collapse",
                    "stage.source.collapse.max",
                    "rag.eval.stageSourceDiversity.collapse");

            double qCompressionEfficiency = maxDouble(m, "q_compression_efficiency", "compression.efficiency");
            double compressionInput = nz(m.get("compression.input.count"), 0);
            double compressionOutput = nz(m.get("compression.output.count"), 0);
            if (qCompressionEfficiency <= 0.0 && compressionInput > 0.0) {
                qCompressionEfficiency = clip01(1.0 - (compressionOutput / compressionInput));
            }

            double qLearningPromotionRisk = maxDouble(m,
                    "q_learning_promotion_risk",
                    "learning.promotion.risk",
                    "learning.quarantine.pressure");
            qLearningPromotionRisk = Math.max(qLearningPromotionRisk, clip01(
                    nz(m.get("learning.quarantine.count"), 0)
                            + (nz(m.get("learning.contamination.signals"), 0) / 3.0)));

            double qHistoryContaminationPressure = maxDouble(m,
                    "q_history_contamination_pressure",
                    "history.contamination.score",
                    "prompt.memory.compressor.contaminationScore",
                    "blackbox.risk.historyContaminationScore");
            qHistoryContaminationPressure = Math.max(qHistoryContaminationPressure, clip01(
                    nz(m.get("history.contamination.signals"), 0) / 3.0));
            double qRequeryCorrectionNeed = maxDouble(m,
                    "q_requery_correction_need",
                    "history.requery.correction.need");
            if (qRequeryCorrectionNeed <= 0.0d && qHistoryContaminationPressure >= 0.35d) {
                qRequeryCorrectionNeed = qHistoryContaminationPressure;
            }
            double qAnchorDropPressure = maxDouble(m,
                    "q_anchor_drop_pressure",
                    "anchor.drop.pressure",
                    "ablation.traceAnchor.maxExpectedDelta",
                    "ablation.traceAnchor.drop.max");
            double qAnchorLanePressure = maxDouble(m,
                    "q_anchor_lane_pressure",
                    "anchor.lane.pressure",
                    "ablation.traceAnchor.maxP",
                    "traceAnchor.lane.pressure");
            double qRouteCorrectionNeed = maxDouble(m,
                    "q_route_correction_need",
                    "route.correction.need",
                    "ablation.traceAnchor.routeCorrectionNeed");
            if (qRouteCorrectionNeed <= 0.0d && qAnchorDropPressure >= 0.18d) {
                qRouteCorrectionNeed = max(qAnchorDropPressure, qFailsoftPressure, qStageDrop);
            }

            out.put("q_retrieval_health", clip01(qRetrievalHealth));
            out.put("q_gpu_gateway_pressure", clip01(qGpuGatewayPressure));
            out.put("q_gpu_hardware_pressure", clip01(qGpuHardwarePressure));
            out.put("q_kg_degradation_pressure", clip01(qKgDegradationPressure));
            out.put("q_graph_dependency_pressure", clip01(qGraphDependencyPressure));
            out.put("q_llm_upstream_pressure", clip01(qLlmUpstreamPressure));
            out.put("q_failsoft_pressure", clip01(qFailsoftPressure));
            out.put("cancelled.count", Math.max(0L, Math.round(nz(m.get("cancelled.count"), 0))));
            out.put("q_gate_quality", clip01(qGateQuality));
            out.put("q_stage_drop", clip01(qStageDrop));
            out.put("q_stage_source_collapse", clip01(qStageSourceCollapse));
            out.put("q_compression_efficiency", clip01(qCompressionEfficiency));
            out.put("q_learning_promotion_risk", clip01(qLearningPromotionRisk));
            out.put("q_history_contamination_pressure", clip01(qHistoryContaminationPressure));
            out.put("q_requery_correction_need", clip01(qRequeryCorrectionNeed));
            out.put("q_anchor_drop_pressure", clip01(qAnchorDropPressure));
            out.put("q_anchor_lane_pressure", clip01(qAnchorLanePressure));
            out.put("q_route_correction_need", clip01(qRouteCorrectionNeed));
            out.put("q_signal_coverage", clip01(signalCoverage(m)));
            out.put("q_overall_health", clip01(overallHealth(m,
                    qRetrievalHealth,
                    qFailsoftPressure,
                    qGateQuality,
                    qStageDrop,
                    qCompressionEfficiency,
                    qLearningPromotionRisk)));

            // Fixed vector slot for regression safety (ordered keys)
            out.put("_order", VECTOR_ORDER);
        } catch (Exception e) {
            TraceStore.put("telemetry.matrix.suppressed.stage", "extract");
            TraceStore.put("telemetry.matrix.suppressed.errorType", "extract_failed");
            TraceStore.put("telemetry.matrix.suppressed.extract", true);
            TraceStore.put("telemetry.matrix.suppressed.extract.errorType", "extract_failed");
            return zeroSnapshot();
        }
        return out;
    }

    private static double overallHealth(Map<String, Object> m,
                                        double retrievalHealth,
                                        double failsoftPressure,
                                        double gateQuality,
                                        double stageDrop,
                                        double compressionEfficiency,
                                        double learningPromotionRisk) {
        double explicit = maxDouble(m, "q_overall_health", "overall.health");
        if (explicit > 0.0) {
            return explicit;
        }
        if (!hasAny(m,
                "q_retrieval_health", "rag.eval.normalized.balancedScore", "retrieval.health", "balancedScore",
                "q_failsoft_pressure", "failsoft.pressure", "provider.disabled.count", "zero.result.count",
                "after.filter.starvation.count", "timeout.count", "cancelled.count", "rate.limit.count",
                "overdrive.failsoft.count",
                "q_gpu_gateway_pressure", "gpu.gateway.unreachable.count", "uaw.gpu-gateway.admission.blocked",
                "uaw.gpu-gateway.admission.blocked.count",
                "q_gpu_hardware_pressure", "gpu.hardware.pressure", "uaw.gpu-hardware.admission.pressure",
                "uaw.gpu-hardware.admission.blocked", "uaw.gpu-hardware.admission.retrainBlocked",
                "uaw.gpu-hardware.admission.status", "uaw.gpu-hardware.admission.pressureLevel",
                "uaw.gpu-hardware.admission.retrainAllowed", "uaw.gpu-hardware.admission.rerankAllowed",
                "uaw.gpu-hardware.admission.embeddingFallbackAllowed",
                "q_kg_degradation_pressure", "q_graph_dependency_pressure", "kg.degradation.pressure",
                "graph.dependency.pressure", "rag.eval.kgAxis.signals", "rag.eval.kgAxis.neo4jStatus",
                "rag.eval.kgAxis.neo4jFailureClass", "retrieval.kg.neo4j.status",
                "retrieval.kg.neo4j.failureClass",
                "q_gate_quality", "gate.quality", "gate.failure.count", "citation.gate.failed",
                "gate.final.failed", "finalSigmoidGate.failed",
                "q_stage_drop", "stage.drop.max", "rag.eval.stageDrop.max",
                "q_compression_efficiency", "compression.efficiency", "compression.input.count",
                "q_learning_promotion_risk", "learning.promotion.risk", "learning.quarantine.count",
                "learning.contamination.signals")) {
            return 0.0;
        }
        double compressionTerm = hasAny(m, "q_compression_efficiency", "compression.efficiency", "compression.input.count")
                ? clip01(compressionEfficiency)
                : 1.0;
        return (0.30 * clip01(retrievalHealth))
                + (0.20 * (1.0 - clip01(failsoftPressure)))
                + (0.20 * clip01(gateQuality))
                + (0.15 * (1.0 - clip01(stageDrop)))
                + (0.05 * compressionTerm)
                + (0.10 * (1.0 - clip01(learningPromotionRisk)));
    }

    private static double signalCoverage(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return 0.0d;
        }
        int present = 0;
        int total = 8;
        if (hasAny(m, "q_retrieval_health", "rag.eval.normalized.balancedScore", "retrieval.health", "balancedScore")) {
            present++;
        }
        if (hasAny(m, "q_failsoft_pressure", "failsoft.pressure", "provider.disabled.pressure",
                "after.filter.starvation.pressure", "provider.disabled.count", "zero.result.count",
                "after.filter.starvation.count", "timeout.count", "cancelled.count", "rate.limit.count",
                "overdrive.failsoft.count",
                "q_gpu_gateway_pressure", "gpu.gateway.unreachable.count", "uaw.gpu-gateway.admission.blocked",
                "uaw.gpu-gateway.admission.blocked.count",
                "q_gpu_hardware_pressure", "gpu.hardware.pressure", "uaw.gpu-hardware.admission.pressure",
                "uaw.gpu-hardware.admission.blocked", "uaw.gpu-hardware.admission.retrainBlocked",
                "uaw.gpu-hardware.admission.status", "uaw.gpu-hardware.admission.pressureLevel",
                "uaw.gpu-hardware.admission.retrainAllowed", "uaw.gpu-hardware.admission.rerankAllowed",
                "uaw.gpu-hardware.admission.embeddingFallbackAllowed",
                "q_kg_degradation_pressure", "q_graph_dependency_pressure", "kg.degradation.pressure",
                "graph.dependency.pressure", "rag.eval.kgAxis.signals", "rag.eval.kgAxis.neo4jStatus",
                "rag.eval.kgAxis.neo4jFailureClass", "retrieval.kg.neo4j.status",
                "retrieval.kg.neo4j.failureClass")) {
            present++;
        }
        if (hasAny(m, "q_gate_quality", "gate.quality", "gate.failure.count", "citation.gate.failed",
                "gate.final.failed", "finalSigmoidGate.failed")) {
            present++;
        }
        if (hasAny(m, "q_stage_drop", "stage.drop.max", "rag.eval.stageDrop.max")) {
            present++;
        }
        if (hasAny(m, "q_stage_source_collapse", "stage.source.collapse.max",
                "rag.eval.stageSourceDiversity.collapse")) {
            present++;
        }
        if (hasAny(m, "q_compression_efficiency", "compression.efficiency", "compression.input.count")) {
            present++;
        }
        if (hasAny(m, "q_learning_promotion_risk", "learning.promotion.risk",
                "learning.quarantine.pressure", "learning.quarantine.count", "learning.contamination.signals")) {
            present++;
        }
        if (hasAny(m, "source.web.count", "source.vector.count", "source.kg.count", "authority.avg",
                "novelty.avg", "contradiction.score", "loss.contribution", "m6_loss_contribution",
                "reranker.cost", "risk.score", "latency.ms", "budget.usage")) {
            present++;
        }
        return total <= 0 ? 0.0d : present / (double) total;
    }

    private static boolean hasAny(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (m.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static double maxDouble(Map<String, Object> m, String... keys) {
        double max = 0.0;
        if (m == null || keys == null) {
            return max;
        }
        for (String key : keys) {
            max = Math.max(max, nz(m.get(key), 0.0));
        }
        return clip01(max);
    }

    private static double gpuHardwareAdmissionPressure(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return 0.0d;
        }
        if (flag(m, "uaw.gpu-hardware.admission.blocked")) {
            return 1.0d;
        }
        String status = label(m.get("uaw.gpu-hardware.admission.status"));
        String pressureLevel = label(m.get("uaw.gpu-hardware.admission.pressureLevel"));
        if ("blocked".equals(status) || "block".equals(pressureLevel)) {
            return 1.0d;
        }
        if ("degraded".equals(status)
                || "warn".equals(pressureLevel)
                || flag(m, "uaw.gpu-hardware.admission.retrainBlocked")
                || explicitFalse(m, "uaw.gpu-hardware.admission.retrainAllowed")
                || explicitFalse(m, "uaw.gpu-hardware.admission.rerankAllowed")
                || explicitFalse(m, "uaw.gpu-hardware.admission.embeddingFallbackAllowed")) {
            return 0.55d;
        }
        return 0.0d;
    }

    private static double kgNeo4jDegradationPressure(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return 0.0d;
        }
        if (hasSignal(m.get("rag.eval.kgAxis.signals"),
                "kg_neo4j_failed", "kg_neo4j_disabled", "kg_neo4j_degraded",
                "kg_sparse_node_failed", "kg_sparse_node_no_graph_path", "kg_sparse_node_degraded")) {
            return 1.0d;
        }
        String status = firstLabel(m,
                "rag.eval.kgAxis.neo4jStatus",
                "rag.eval.kgAxis.sparseNodeStatus",
                "retrieval.kg.neo4j.status",
                "retrieval.dependency.kg.status");
        String reason = firstLabel(m,
                "rag.eval.kgAxis.neo4jDisabledReason",
                "rag.eval.kgAxis.sparseNodeDisabledReason",
                "retrieval.kg.neo4j.disabledReason");
        String failureClass = firstLabel(m,
                "rag.eval.kgAxis.neo4jFailureClass",
                "rag.eval.kgAxis.sparseNodeFailureClass",
                "retrieval.kg.neo4j.failureClass",
                "retrieval.dependency.kg.failureClass");
        if ("failed".equals(status) || "disabled".equals(status) || "degraded".equals(status)
                || "no_graph_path".equals(status)) {
            return 1.0d;
        }
        if (!failureClass.isBlank() && !"none".equals(failureClass) && !"ok".equals(failureClass)) {
            return 1.0d;
        }
        if (reason.contains("failed") || reason.contains("disabled") || reason.contains("no_graph_path")
                || reason.startsWith("missing_")) {
            return 1.0d;
        }
        return 0.0d;
    }

    private static boolean hasSignal(Object raw, String... expected) {
        if (raw == null || expected == null) {
            return false;
        }
        if (raw instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (matchesSignal(row, expected)) {
                    return true;
                }
            }
            return false;
        }
        return matchesSignal(raw, expected);
    }

    private static boolean matchesSignal(Object raw, String... expected) {
        String value = label(raw);
        if (value.isBlank()) {
            return false;
        }
        for (String signal : expected) {
            if (signal != null && value.contains(label(signal))) {
                return true;
            }
        }
        return false;
    }

    private static String firstLabel(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (m.containsKey(key)) {
                String value = label(m.get(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static boolean explicitFalse(Map<String, Object> m, String key) {
        return m != null && m.containsKey(key) && !flag(m, key);
    }

    private static boolean flag(Map<String, Object> m, String key) {
        if (m == null || key == null || !m.containsKey(key)) {
            return false;
        }
        Object value = m.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = label(value);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static String label(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static double max(double... values) {
        double best = 0.0d;
        if (values == null) {
            return best;
        }
        for (double value : values) {
            if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                best = Math.max(best, value);
            }
        }
        return best;
    }

    private static Map<String, Object> zeroSnapshot() {
        Map<String,Object> zero = new LinkedHashMap<>();
        zero.put("matrix.schemaVersion", SCHEMA_VERSION);
        zero.put("m1_source_mix_web", 0.0);
        zero.put("m1_source_mix_vector", 0.0);
        zero.put("m1_source_mix_kg", 0.0);
        zero.put("m2_authority", 0.0);
        zero.put("m3_novelty", 0.0);
        zero.put("m4_contradiction", 0.0);
        zero.put("m5_rerank_cost", 0.0);
        zero.put("m6_loss_contribution", 0.0);
        zero.put("m7_risk", 0.0);
        zero.put("m8_latency", 0.0);
        zero.put("m9_budget", 0.0);
        zero.put("q_retrieval_health", 0.0);
        zero.put("q_gpu_gateway_pressure", 0.0);
        zero.put("q_gpu_hardware_pressure", 0.0);
        zero.put("q_failsoft_pressure", 0.0);
        zero.put("q_gate_quality", 0.0);
        zero.put("q_stage_drop", 0.0);
        zero.put("q_stage_source_collapse", 0.0);
        zero.put("q_compression_efficiency", 0.0);
        zero.put("q_learning_promotion_risk", 0.0);
        zero.put("q_history_contamination_pressure", 0.0);
        zero.put("q_requery_correction_need", 0.0);
        zero.put("q_anchor_drop_pressure", 0.0);
        zero.put("q_anchor_lane_pressure", 0.0);
        zero.put("q_route_correction_need", 0.0);
        zero.put("q_signal_coverage", 0.0);
        zero.put("q_overall_health", 0.0);
        zero.put("_order", VECTOR_ORDER);
        return zero;
    }
}
