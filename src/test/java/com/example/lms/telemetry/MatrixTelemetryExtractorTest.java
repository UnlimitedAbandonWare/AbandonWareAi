package com.example.lms.telemetry;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixTelemetryExtractorTest {

    @Test
    void emitsV2QuantitativeScalarsWithoutChangingVectorOrder() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source.web.count", 2);
        input.put("source.vector.count", 2);
        input.put("rag.eval.normalized.balancedScore", 0.82d);
        input.put("provider.disabled.count", 1);
        input.put("zero.result.count", 1);
        input.put("after.filter.starvation.count", 1);
        input.put("gate.failure.count", 1);
        input.put("stage.drop.max", 1.2d);
        input.put("loss.contribution", 0.44d);
        input.put("compression.input.count", 16);
        input.put("compression.output.count", 4);
        input.put("learning.quarantine.count", 1);
        input.put("learning.contamination.signals", List.of("context_contamination_score"));
        input.put("history.contamination.score", 0.52d);
        input.put("history.contamination.signals", 2);
        input.put("history.requery.correction.need", 0.52d);
        input.put("ablation.traceAnchor.maxExpectedDelta", 0.44d);
        input.put("ablation.traceAnchor.maxP", 0.80d);
        input.put("ablation.traceAnchor.routeCorrectionNeed", 0.44d);

        Map<String, Object> snapshot = extractor.extract(input);

        assertEquals(MatrixTelemetryExtractor.SCHEMA_VERSION, snapshot.get("matrix.schemaVersion"));
        assertEquals(0.82d, ((Number) snapshot.get("q_retrieval_health")).doubleValue(), 0.0001d);
        assertTrue(((Number) snapshot.get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertTrue(((Number) snapshot.get("q_gate_quality")).doubleValue() < 1.0d);
        assertEquals(1.0d, ((Number) snapshot.get("q_stage_drop")).doubleValue(), 0.0001d);
        assertEquals(0.44d, ((Number) snapshot.get("m6_loss_contribution")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_stage_source_collapse")).doubleValue(), 0.0001d);
        assertEquals(0.75d, ((Number) snapshot.get("q_compression_efficiency")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_learning_promotion_risk")).doubleValue(), 0.0001d);
        assertEquals(2.0d / 3.0d, ((Number) snapshot.get("q_history_contamination_pressure")).doubleValue(),
                0.0001d);
        assertEquals(0.52d, ((Number) snapshot.get("q_requery_correction_need")).doubleValue(), 0.0001d);
        assertEquals(0.44d, ((Number) snapshot.get("q_anchor_drop_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.80d, ((Number) snapshot.get("q_anchor_lane_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.44d, ((Number) snapshot.get("q_route_correction_need")).doubleValue(), 0.0001d);
        assertEquals(0.875d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        assertTrue(((Number) snapshot.get("q_overall_health")).doubleValue() >= 0.0d);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(List.of(
                "m1_source_mix_web", "m1_source_mix_vector", "m1_source_mix_kg",
                "m2_authority", "m3_novelty", "m4_contradiction", "m5_rerank_cost",
                "m7_risk", "m8_latency", "m9_budget"), order);
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void failSoftZeroFillKeepsSchemaAndVectorContract() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();
        Iterable<Object> brokenIterable = () -> {
            throw new IllegalStateException("broken metric");
        };

        Map<String, Object> snapshot = extractor.extract(Map.of("source.web.count", brokenIterable));

        assertEquals(MatrixTelemetryExtractor.SCHEMA_VERSION, snapshot.get("matrix.schemaVersion"));
        assertEquals(0.0d, ((Number) snapshot.get("m1_source_mix_web")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_overall_health")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_anchor_drop_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_anchor_lane_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("q_route_correction_need")).doubleValue(), 0.0001d);
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void nonFiniteMetricsZeroFillAndLeaveSuppressionBreadcrumb() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();
        TraceStore.clear();

        Map<String, Object> snapshot = extractor.extract(Map.of(
                "source.web.count", Double.POSITIVE_INFINITY,
                "source.vector.count", Double.NaN,
                "authority.avg", Double.NaN,
                "reranker.cost", Double.POSITIVE_INFINITY,
                "budget.usage", "Infinity"));

        assertEquals(0.0d, ((Number) snapshot.get("m1_source_mix_web")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("m1_source_mix_vector")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("m2_authority")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("m5_rerank_cost")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) snapshot.get("m9_budget")).doubleValue(), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("telemetry.matrix.suppressed.numberParse"));
        assertEquals("invalid_number", TraceStore.get("telemetry.matrix.suppressed.numberParse.errorType"));
        assertEquals("numberParse", TraceStore.get("telemetry.matrix.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("telemetry.matrix.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
    }

    @Test
    void malformedMetricNumberDoesNotLeakRawValueInSuppressionBreadcrumb() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();
        TraceStore.clear();
        String raw = "ownerToken=raw-secret";

        Map<String, Object> snapshot = extractor.extract(Map.of("authority.avg", raw));

        assertEquals(0.0d, ((Number) snapshot.get("m2_authority")).doubleValue(), 0.0001d);
        assertEquals(Boolean.TRUE, TraceStore.get("telemetry.matrix.suppressed.numberParse"));
        assertEquals("invalid_number", TraceStore.get("telemetry.matrix.suppressed.numberParse.errorType"));
        assertEquals("numberParse", TraceStore.get("telemetry.matrix.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("telemetry.matrix.suppressed.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(raw), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
    }

    @Test
    void gpuGatewayPressureIsSidecarAndFeedsFailsoftPressure() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of(
                "uaw.gpu-gateway.admission.blocked", true,
                "uaw.gpu-gateway.admission.blocked.count", 1));

        assertEquals(1.0d, ((Number) snapshot.get("q_gpu_gateway_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_failsoft_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.125d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void gpuHardwarePressureIsSidecarAndFeedsFailsoftPressure() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of(
                "uaw.gpu-hardware.admission.status", "degraded",
                "uaw.gpu-hardware.admission.pressureLevel", "warn",
                "uaw.gpu-hardware.admission.retrainAllowed", false));

        assertEquals(0.55d, ((Number) snapshot.get("q_gpu_hardware_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.55d, ((Number) snapshot.get("q_failsoft_pressure")).doubleValue(), 0.0001d);
        assertEquals(0.125d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void cancelledCountFeedsFailsoftCoverageAndOverallHealth() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of("cancelled.count", 1));

        assertTrue(((Number) snapshot.get("q_failsoft_pressure")).doubleValue() > 0.0d);
        assertEquals(1L, ((Number) snapshot.get("cancelled.count")).longValue());
        assertEquals(0.125d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        assertTrue(((Number) snapshot.get("q_overall_health")).doubleValue() > 0.0d);
    }

    @Test
    void kgNeo4jSignalsAreSidecarAndFeedGraphDependencyPressure() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of(
                "rag.eval.kgAxis.signals", List.of("kg_neo4j_failed", "kg_neo4j_degraded"),
                "rag.eval.kgAxis.neo4jStatus", "failed",
                "retrieval.kg.neo4j.failureClass", "silent-failure"));

        assertEquals(1.0d, ((Number) snapshot.get("q_kg_degradation_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_graph_dependency_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_failsoft_pressure")).doubleValue(), 0.0001d);
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void sparseNodeKgAxisSignalsFeedKgAndGraphDependencyPressure() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of(
                "rag.eval.kgAxis.signals", List.of("kg_sparse_node_failed", "kg_sparse_node_no_graph_path"),
                "rag.eval.kgAxis.sparseNodeStatus", "no_graph_path",
                "rag.eval.kgAxis.sparseNodeDisabledReason", "missing_graph_path",
                "rag.eval.kgAxis.sparseNodeFailureClass", "sparse_node_no_graph_path",
                "rawQuery", "raw sparse node query must not leak"));

        assertEquals(1.0d, ((Number) snapshot.get("q_kg_degradation_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_graph_dependency_pressure")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) snapshot.get("q_failsoft_pressure")).doubleValue(), 0.0001d);
        assertFalse(String.valueOf(snapshot).contains("raw sparse node query must not leak"));
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }

    @Test
    void signalCoverageDistinguishesMeasuredZeroFromMissingSignals() {
        MatrixTelemetryExtractor extractor = new MatrixTelemetryExtractor();

        Map<String, Object> snapshot = extractor.extract(Map.of("rag.eval.normalized.balancedScore", 0.0d));

        assertEquals(0.0d, ((Number) snapshot.get("q_retrieval_health")).doubleValue(), 0.0001d);
        assertEquals(0.125d, ((Number) snapshot.get("q_signal_coverage")).doubleValue(), 0.0001d);
        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) snapshot.get("_order");
        assertEquals(10, order.size());
        assertFalse(order.stream().anyMatch(key -> key.startsWith("q_")));
    }
}
