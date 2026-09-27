package com.example.lms.moe;

import com.example.lms.search.TraceStore;
import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgbSoakReportServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
    }

    @Test
    void runAddsRawAndNormalizedNeutralMetricsWithoutCallingOpenClaw() throws Exception {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
        ObjectMapper objectMapper = new ObjectMapper();
        RgbSoakReportService service = new RgbSoakReportService(
                new FakeOrchestrator(),
                objectMapper,
                new RgbMoeProperties());
        RgbStrategySelector.Decision decision = new RgbStrategySelector.Decision(
                RgbStrategySelector.Strategy.R_ONLY,
                List.of(),
                List.of(),
                null);

        RgbSoakReport report = service.run(
                "session-1",
                List.of("hit query", "miss query"),
                decision,
                0,
                false);

        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION, report.debug().get("normalizedMetricSchema"));
        assertEquals("offline", report.debug().get("openclawEvaluator.mode"));

        Map<String, Object> summary = map(report.debug().get("ragNeutralSummary"));
        Map<String, Object> rOnlySummary = map(summary.get("R_ONLY"));
        assertEquals(2, rOnlySummary.get("calls"));
        assertEquals(1L, rOnlySummary.get("hits"));
        assertEquals(2, rOnlySummary.get("docCount"));
        assertEquals(2, rOnlySummary.get("evidenceCount"));
        assertEquals(2, rOnlySummary.get("distinctSources"));

        RgbSoakMetrics metrics = report.metricsByStrategy().get("R_ONLY");
        assertNotNull(metrics);
        assertEquals(2, metrics.queries());
        assertEquals(2, metrics.calls());
        assertEquals(2, metrics.docCount());
        assertEquals(2, metrics.evidenceCount());
        assertEquals(2, metrics.distinctSources());
        assertEquals(1.0d, metrics.avgDocsPerQuery(), 1e-9);
        assertEquals(0.5d, metrics.retrievalHitRate(), 1e-9);
        assertEquals(1.0d, metrics.evidenceCoverage(), 1e-9);
        assertEquals(0.5d, metrics.fallbackRate(), 1e-9);

        NormalizedRagMetrics normalized = metrics.normalized();
        assertNotNull(normalized);
        assertEquals(0.5d, normalized.retrievalHitRate(), 1e-9);
        assertEquals(1.0d, normalized.evidenceCoverage(), 1e-9);
        assertEquals(1.0d, normalized.sourceDiversity(), 1e-9);
        assertEquals(0.125d, normalized.resultDepth(), 1e-9);
        assertEquals(0.5d, normalized.fallbackCost(), 1e-9);
        assertTrue(normalized.balancedScore() > 0.5d);

        assertEquals(normalized.balancedScore(), number(rOnlySummary.get("balancedScore")), 1e-9);

        List<Map<String, Object>> weakPoints = listOfMaps(report.debug().get("ragNeutralWeakPoints"));
        assertTrue(hasLabel(weakPoints, "retrieval_starvation"));
        assertTrue(hasLabel(weakPoints, "thin_results"));
        assertTrue(hasLabel(weakPoints, "fallback_dependency"));
        assertTrue(weakPoints.stream().allMatch(row ->
                row.containsKey("comparator")
                        && row.containsKey("severity")
                        && row.containsKey("stage")
                        && "soak".equals(row.get("scope"))
                        && Integer.valueOf(2).equals(row.get("sampleCount"))
                        && "rgb-soak".equals(row.get("aggregationWindow"))));

        Map<String, Object> thresholds = map(report.debug().get("ragNeutralThresholds"));
        assertEquals(0.60d, number(thresholds.get("retrievalHitRate.min")), 1e-9);
        assertEquals(0.30d, number(thresholds.get("fallbackCost.max")), 1e-9);

        Map<String, Object> payload = map(report.debug().get("openclawEvaluator.promptPayload"));
        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION, payload.get("schema"));
        assertEquals(Boolean.TRUE, payload.get("advisoryOnly"));
        assertEquals("ollama/qwen3.5:9b", payload.get("modelHint"));
        assertNotNull(payload.get("strategySummaries"));
        assertNotNull(payload.get("nextInspectionCandidates"));

        String promptPayloadJson = objectMapper.writeValueAsString(payload);
        assertFalse(promptPayloadJson.contains("hit query"));
        assertFalse(promptPayloadJson.contains("miss query"));
        assertFalse(promptPayloadJson.contains("snippet"));
        assertFalse(promptPayloadJson.contains("https://example.com/a"));

        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION, TraceStore.get("rgb.soak.schema"));
        assertEquals(normalized.balancedScore(),
                number(TraceStore.get("rgb.soak.strategy.R_ONLY.balancedScore")),
                1e-9);
        assertEquals(normalized.retrievalHitRate(),
                number(TraceStore.get("rag.eval.normalized.retrievalHitRate")),
                1e-9);
        assertEquals(normalized.evidenceCoverage(),
                number(TraceStore.get("rag.eval.normalized.evidenceCoverage")),
                1e-9);
        assertEquals(normalized.balancedScore(),
                number(TraceStore.get("rag.eval.normalized.balancedScore")),
                1e-9);
        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION,
                FaithfulnessMetricSnapshotStore.get("rag.eval.normalized.schemaVersion"));
    }

    @Test
    void kgVariantEnabledAddsComparisonStrategiesWithoutChangingDefaultStrategy() {
        ObjectMapper objectMapper = new ObjectMapper();
        RgbMoeProperties props = new RgbMoeProperties();
        props.setKgVariantEnabled(true);
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        RgbSoakReportService service = new RgbSoakReportService(orchestrator, objectMapper, props);
        RgbStrategySelector.Decision decision = new RgbStrategySelector.Decision(
                RgbStrategySelector.Strategy.R_ONLY,
                List.of(RgbStrategySelector.Strategy.G_ONLY),
                List.of(),
                null);

        RgbSoakReport report = service.run(
                "session-kg",
                List.of("hit query"),
                decision,
                0,
                false);

        assertEquals(Boolean.TRUE, report.debug().get("kgVariantEnabled"));
        assertTrue(report.metricsByStrategy().containsKey("R_ONLY"));
        assertTrue(report.metricsByStrategy().containsKey("R_ONLY_KG"));
        assertTrue(report.metricsByStrategy().containsKey("G_ONLY"));
        assertTrue(report.metricsByStrategy().containsKey("G_ONLY_KG"));
        assertEquals(List.of(false, true, false, true), orchestrator.useKgValues);
        assertTrue(orchestrator.planIds.contains("rgb.soak.R_ONLY"));
        assertTrue(orchestrator.planIds.contains("rgb.soak.R_ONLY.kg"));
        assertTrue(orchestrator.planIds.contains("rgb.soak.G_ONLY"));
        assertTrue(orchestrator.planIds.contains("rgb.soak.G_ONLY.kg"));
        Map<String, Object> summary = map(report.debug().get("ragNeutralSummary"));
        Map<String, Object> rOnlyKgSummary = map(summary.get("R_ONLY_KG"));
        assertEquals(2, rOnlyKgSummary.get("relationThumbnailCandidateCount"));
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailSelectedCount"));
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailDroppedCount"));
        assertEquals(0.5d, number(rOnlyKgSummary.get("relationThumbnailSelectionRate")), 1e-9);
        assertEquals(0.5d, number(rOnlyKgSummary.get("relationThumbnailDropRate")), 1e-9);
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailAnchorSeedUsedCount"));
        assertEquals("anchor_seed", rOnlyKgSummary.get("relationThumbnailSelectionReason"));
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailSignalAppliedCount"));
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailSignalSlicedCount"));
        assertEquals(1, rOnlyKgSummary.get("relationThumbnailSignalAnchorSeedCount"));
        assertEquals(0, rOnlyKgSummary.get("relationThumbnailSignalNoSelectionCount"));
        assertEquals(2, rOnlyKgSummary.get("relationThumbnailSliceMapCount"));
        assertEquals(List.of("RELATIONSHIP_SUPPORTS", "RELATIONSHIP_CONTRASTS"),
                rOnlyKgSummary.get("relationThumbnailRelationKinds"));
        assertEquals(List.of("breadcrumb", "summary"),
                rOnlyKgSummary.get("relationThumbnailContextLayers"));
        assertEquals(Map.of("breadcrumb", 1, "summary", 1),
                rOnlyKgSummary.get("relationThumbnailContextLayerCounts"));
        assertEquals(12, rOnlyKgSummary.get("uawRelationThumbnailInputAnchorCount"));
        assertEquals(8, rOnlyKgSummary.get("uawRelationThumbnailSelectedAnchorCount"));
        assertEquals(8, rOnlyKgSummary.get("uawRelationThumbnailAnchorBudget"));
        assertEquals(16, rOnlyKgSummary.get("uawRelationThumbnailPairBudget"));
        assertEquals(16, rOnlyKgSummary.get("uawRelationThumbnailEmittedPairCount"));
        assertEquals(Boolean.TRUE, rOnlyKgSummary.get("uawRelationThumbnailSliced"));
        assertEquals(1, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailAnchorSeedUsedCount"));
        assertEquals(1, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailSignalAppliedCount"));
        assertEquals(1, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailSignalSlicedCount"));
        assertEquals(1, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailSignalAnchorSeedCount"));
        assertEquals(0, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailSignalNoSelectionCount"));
        assertEquals(2, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailSliceMapCount"));
        assertEquals(List.of("RELATIONSHIP_SUPPORTS", "RELATIONSHIP_CONTRASTS"),
                TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailRelationKinds"));
        assertEquals(List.of("breadcrumb", "summary"),
                TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailContextLayers"));
        assertEquals(Map.of("breadcrumb", 1, "summary", 1),
                TraceStore.get("rgb.soak.strategy.R_ONLY_KG.relationThumbnailContextLayerCounts"));
        assertEquals(8, TraceStore.get("rgb.soak.strategy.R_ONLY_KG.uawRelationThumbnailAnchorBudget"));
        assertFalse(String.valueOf(rOnlyKgSummary).contains("Anchor09"));
        assertFalse(String.valueOf(report.debug()).contains("High fan-out thumbnail"));
    }

    @Test
    void reportDebugStoresHashOnlyReportFileDiagnosticsWhenWritingFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RgbMoeProperties props = new RgbMoeProperties();
        Path reportDir = tempDir.resolve("private-soak-output");
        props.setSoakReportDir(reportDir.toString());
        RgbSoakReportService service = new RgbSoakReportService(
                new FakeOrchestrator(),
                objectMapper,
                props);
        RgbStrategySelector.Decision decision = new RgbStrategySelector.Decision(
                RgbStrategySelector.Strategy.R_ONLY,
                List.of(),
                List.of(),
                null);

        RgbSoakReport report = service.run(
                "session-file",
                List.of("hit query"),
                decision,
                0,
                true);

        assertFalse(report.debug().containsKey("reportFile"));
        assertFalse(report.debug().toString().contains(tempDir.toString()));
        assertTrue(report.debug().get("reportFileHash") instanceof String);
        assertTrue(report.debug().get("reportFileLength") instanceof Number);
        assertTrue(report.debug().get("reportPathHash") instanceof String);
        assertTrue(report.debug().get("reportPathLength") instanceof Number);
        try (var paths = Files.list(reportDir)) {
            List<Path> files = paths.toList();
            assertEquals(1, files.size());
            assertFalse(files.get(0).getFileName().toString().endsWith(".tmp"));
            assertTrue(objectMapper.readTree(files.get(0).toFile()).isObject());
        }
    }

    @Test
    void failedSerializationPreservesPreviousCompleteDailyReport() throws Exception {
        RgbMoeProperties props = new RgbMoeProperties();
        props.setSoakReportDir(tempDir.toString());
        Path reportPath = tempDir.resolve(
                DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneId.systemDefault())) + "_rgb.json");
        String previousReport = "{\"complete\":true}";
        Files.writeString(reportPath, previousReport, StandardCharsets.UTF_8);

        ObjectMapper failingMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(RgbSoakReport.class, new JsonSerializer<>() {
            @Override
            public void serialize(RgbSoakReport value,
                                  JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeStartObject();
                generator.writeStringField("partial", "report");
                generator.flush();
                throw new IOException("forced serialization failure");
            }
        });
        failingMapper.registerModule(module);
        RgbSoakReportService service = new RgbSoakReportService(
                new FakeOrchestrator(),
                failingMapper,
                props);

        service.run("session-file-failure", List.of(), null, 0, true);

        assertEquals(previousReport, Files.readString(reportPath, StandardCharsets.UTF_8));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of(reportPath), files.toList());
        }
    }

    @Test
    void serviceDoesNotShellOutToOpenClawAtRuntime() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/moe/RgbSoakReportService.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("ProcessBuilder"));
        assertFalse(source.contains("openclaw.cmd"));
        assertTrue(source.contains("TraceStore.put(\"rgb.soak.suppressed.intMetric\", true)"));
        assertTrue(source.contains("TraceStore.put(\"rgb.soak.suppressed.intMetric.errorType\", \"invalid_number\")"));
        assertTrue(source.contains("TraceStore.put(\"rgb.soak.suppressed.stage\", \"intMetric\")"));
        assertTrue(source.contains("TraceStore.put(\"rgb.soak.suppressed.errorType\", \"invalid_number\")"));

        TraceStore.clear();
        Method intMetric = RgbSoakReportService.class.getDeclaredMethod("intMetric", Map.class, String.class);
        intMetric.setAccessible(true);
        assertEquals(0, intMetric.invoke(null, Map.of("value", "not-a-number-owner-token"), "value"));
        assertEquals(Boolean.TRUE, TraceStore.get("rgb.soak.suppressed.intMetric"));
        assertEquals("invalid_number", TraceStore.get("rgb.soak.suppressed.intMetric.errorType"));
        assertEquals("intMetric", TraceStore.get("rgb.soak.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("rgb.soak.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("NumberFormatException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("owner-token"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertNotNull(value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        assertNotNull(value);
        return (List<Map<String, Object>>) value;
    }

    private static boolean hasLabel(List<Map<String, Object>> weakPoints, String label) {
        return weakPoints.stream().anyMatch(m -> label.equals(m.get("label")));
    }

    private static double number(Object value) {
        assertTrue(value instanceof Number);
        return ((Number) value).doubleValue();
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private final List<Boolean> useKgValues = new java.util.ArrayList<>();
        private final List<String> planIds = new java.util.ArrayList<>();

        @Override
        public QueryResponse query(QueryRequest req) {
            QueryResponse response = new QueryResponse();
            response.debug = new LinkedHashMap<>();
            if (req != null) {
                useKgValues.add(req.useKg);
                planIds.add(req.planId);
            }
            if (req != null && req.query != null && req.query.contains("hit")) {
                response.results.add(doc("WEB", "snippet", Map.of()));
                response.results.add(doc("VECTOR", "", Map.of("url", "https://example.com/a")));
                if (req.useKg) {
                    TraceStore.put("uaw.thumbnail.relationThumbnail.inputAnchorCount", 12);
                    TraceStore.put("uaw.thumbnail.relationThumbnail.selectedAnchorCount", 8);
                    TraceStore.put("uaw.thumbnail.relationThumbnail.anchorBudget", 8);
                    TraceStore.put("uaw.thumbnail.relationThumbnail.pairBudget", 16);
                    TraceStore.put("uaw.thumbnail.relationThumbnail.emittedPairCount", 16);
                    TraceStore.put("uaw.thumbnail.relationThumbnail.sliced", true);
                    response.debug.put("rag.eval.kgAxis", Map.of(
                            "relationThumbnailCandidateCount", 2,
                            "relationThumbnailSelectedCount", 1,
                            "relationThumbnailDroppedCount", 1,
                            "relationThumbnailSelectionRate", 0.5d,
                            "relationThumbnailDropRate", 0.5d,
                            "relationThumbnailSelectionReason", "anchor_seed",
                            "relationThumbnailAnchorSeedUsed", true,
                            "signals", List.of(
                                    "kg_relation_thumbnail_applied",
                                    "kg_relation_thumbnail_sliced",
                                    "kg_relation_thumbnail_anchor_seed")));
                    response.debug.put("retrieval.kg.relationThumbnail.sliceMap", List.of(
                            Map.of("hash", "aaaaaaaaaaaa", "relationKind", "RELATIONSHIP_SUPPORTS",
                                    "contextLayer", "breadcrumb"),
                            Map.of("hash", "bbbbbbbbbbbb", "relationKind", "RELATIONSHIP_CONTRASTS",
                                    "contextLayer", "summary")));
                }
            } else {
                response.debug.put("fallback", "model_knowledge");
            }
            return response;
        }

        private static Doc doc(String source, String snippet, Map<String, Object> meta) {
            Doc doc = new Doc();
            doc.source = source;
            doc.snippet = snippet;
            doc.meta = meta;
            return doc;
        }
    }
}
