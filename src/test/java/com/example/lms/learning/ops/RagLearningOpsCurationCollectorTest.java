package com.example.lms.learning.ops;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagLearningOpsCurationCollectorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void disabledCollectorDoesNotTouchDashboardOrFiles() {
        RagLearningOpsDashboardService dashboard = mock(RagLearningOpsDashboardService.class);
        Path output = tempDir.resolve("curation.jsonl");
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.learning-ops.collector.enabled", "false")
                .withProperty("awx.learning-ops.collector.output-path", output.toString());
        RagLearningOpsCurationCollector collector = new RagLearningOpsCurationCollector(dashboard, objectMapper, env);

        Map<String, Object> status = collector.collectOnce("manual");

        assertEquals("disabled_by_config", status.get("status"));
        assertEquals(false, status.get("writesDataset"));
        assertFalse(Files.exists(output));
        assertEquals("disabled_by_config", TraceStore.get("awx.learning-ops.collector.status"));
        verifyNoInteractions(dashboard);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enabledCollectorWritesRedactedCurationRecordWithoutDatasetWrites() throws Exception {
        RagLearningOpsDashboardService dashboard = mock(RagLearningOpsDashboardService.class);
        Path output = tempDir.resolve("learning-ops-curation.jsonl");
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.node.role", "macmini-control-plane")
                .withProperty("awx.node.execution-node", "macmini-m4-16gb")
                .withProperty("awx.node.learning-loop-mode", "curate-observe-schedule")
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", output.toString())
                .withProperty("awx.learning-ops.collector.max-items", "10");
        when(dashboard.overview(50)).thenReturn(Map.of("opsLedger", Map.of(
                "datasetPipelineQueue", Map.of(
                        "status", "ready",
                        "itemCount", 1,
                        "items", List.of(curationItem())))));
        RagLearningOpsCurationCollector collector = new RagLearningOpsCurationCollector(dashboard, objectMapper, env);

        Map<String, Object> status = collector.collectOnce("manual");

        assertEquals("written", status.get("status"));
        assertEquals(false, status.get("writesDataset"));
        assertTrue(Files.exists(output));
        String line = Files.readString(output, StandardCharsets.UTF_8);
        assertFalse(line.contains("owner-token-must-not-leak"));
        assertFalse(line.contains("raw query must not leak"));
        assertFalse(line.contains("payload must not leak"));
        assertFalse(line.contains("secret-key-must-not-leak"));
        assertFalse(line.contains("train_rag_curated.jsonl"));

        Map<String, Object> record = objectMapper.readValue(line, new TypeReference<>() {
        });
        assertEquals("[AWX][learning-ops][collector]", record.get("checkpoint"));
        assertEquals("macmini-control-plane", record.get("nodeRole"));
        assertEquals("macmini-m4-16gb", record.get("executionNode"));
        assertEquals(false, record.get("writesDataset"));
        assertEquals(true, record.get("requiresReview"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) record.get("items");
        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("demote_heavy_work", item.get("action"));
        assertEquals("gpu_hardware_pressure", item.get("reason"));
        assertEquals(false, item.get("writesDataset"));
        assertEquals(true, item.get("requiresReview"));
        assertFalse(item.containsKey("ownerToken"));
        assertFalse(item.containsKey("rawQuery"));
        assertFalse(item.containsKey("payload"));
        assertFalse(item.containsKey("datasetPath"));
        Map<String, Object> gpuAdmission = (Map<String, Object>) item.get("gpuAdmission");
        assertEquals("gpu_memory_pressure", gpuAdmission.get("hardwareReason"));
        Map<String, Object> counts = (Map<String, Object>) item.get("counts");
        assertEquals(3.0d, counts.get("attempted"));
        assertEquals("written", TraceStore.get("awx.learning-ops.collector.status"));
        assertEquals(1, TraceStore.get("awx.learning-ops.collector.itemCount"));
        verify(dashboard).overview(50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidNumericCollectorValuesLeaveRedactedTraceBreadcrumbs() throws Exception {
        RagLearningOpsDashboardService dashboard = mock(RagLearningOpsDashboardService.class);
        Path output = tempDir.resolve("learning-ops-curation-invalid-number.jsonl");
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", output.toString())
                .withProperty("awx.learning-ops.collector.max-items", "bad-max-items");
        when(dashboard.overview(50)).thenReturn(Map.of("opsLedger", Map.of(
                "datasetPipelineQueue", Map.of(
                        "status", "ready",
                        "itemCount", 1,
                        "items", List.of(curationItemWithInvalidNumbers())))));
        RagLearningOpsCurationCollector collector = new RagLearningOpsCurationCollector(dashboard, objectMapper, env);

        Map<String, Object> status = collector.collectOnce("manual");

        assertEquals("written", status.get("status"));
        String line = Files.readString(output, StandardCharsets.UTF_8);
        Map<String, Object> record = objectMapper.readValue(line, new TypeReference<>() {
        });
        List<Map<String, Object>> items = (List<Map<String, Object>>) record.get("items");
        Map<String, Object> item = items.get(0);
        Map<String, Object> gpuAdmission = (Map<String, Object>) item.get("gpuAdmission");
        Map<String, Object> counts = (Map<String, Object>) item.get("counts");
        assertFalse(gpuAdmission.containsKey("hardwarePressure"));
        assertFalse(counts.containsKey("accepted"));
        assertFalse(counts.containsKey("webReturned"));
        assertEquals(true, TraceStore.get("awx.learning-ops.collector.suppressed.copyNumber"));
        assertEquals("invalid_number",
                TraceStore.get("awx.learning-ops.collector.suppressed.copyNumber.errorType"));
        assertEquals(true, TraceStore.get("awx.learning-ops.collector.suppressed.clampInt"));
        assertEquals("invalid_number",
                TraceStore.get("awx.learning-ops.collector.suppressed.clampInt.errorType"));
    }

    @Test
    void collectorTraceStoresHashOnlyOutputFileDiagnostics() throws Exception {
        RagLearningOpsDashboardService dashboard = mock(RagLearningOpsDashboardService.class);
        String rawFileName = "private-curation-target.jsonl";
        Path output = tempDir.resolve(rawFileName);
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", output.toString())
                .withProperty("awx.learning-ops.collector.max-items", "10");
        when(dashboard.overview(50)).thenReturn(Map.of("opsLedger", Map.of(
                "datasetPipelineQueue", Map.of(
                        "status", "ready",
                        "itemCount", 1,
                        "items", List.of(curationItem())))));
        RagLearningOpsCurationCollector collector = new RagLearningOpsCurationCollector(dashboard, objectMapper, env);

        Map<String, Object> status = collector.collectOnce("manual");

        assertEquals("written", status.get("status"));
        assertFalse(status.toString().contains(rawFileName));
        assertFalse(status.containsKey("outputFile"));
        assertEquals(SafeRedactor.hashValue(rawFileName), status.get("outputFileHash"));
        assertEquals(rawFileName.length(), status.get("outputFileLength"));
        assertEquals(SafeRedactor.hashValue(output.toString()), status.get("outputPathHash"));
        assertFalse(TraceStore.getAll().toString().contains(rawFileName));
        assertEquals(SafeRedactor.hashValue(rawFileName),
                TraceStore.get("awx.learning-ops.collector.outputFileHash"));
        assertEquals(rawFileName.length(),
                TraceStore.get("awx.learning-ops.collector.outputFileLength"));
        assertEquals(SafeRedactor.hashValue(output.toString()),
                TraceStore.get("awx.learning-ops.collector.outputPathHash"));
    }

    @Test
    void enabledCollectorSkipsFileWhenQueueHasNoCandidates() {
        RagLearningOpsDashboardService dashboard = mock(RagLearningOpsDashboardService.class);
        Path output = tempDir.resolve("empty-curation.jsonl");
        MockEnvironment env = new MockEnvironment()
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", output.toString());
        when(dashboard.overview(50)).thenReturn(Map.of("opsLedger", Map.of(
                "datasetPipelineQueue", Map.of(
                        "status", "empty",
                        "items", List.of()))));
        RagLearningOpsCurationCollector collector = new RagLearningOpsCurationCollector(dashboard, objectMapper, env);

        Map<String, Object> status = collector.collectOnce("manual");

        assertEquals("no_candidates", status.get("status"));
        assertEquals(0, status.get("itemCount"));
        assertFalse(Files.exists(output));
        assertEquals("no_candidates", TraceStore.get("awx.learning-ops.collector.status"));
        verify(dashboard).overview(50);
    }

    @Test
    void numericCollectorParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/ops/RagLearningOpsCurationCollector.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static void copyNumber(Map<String, Object> out, Map<?, ?> source, String key)");
        assertParserCatchNarrowed(source, "private static int clampInt(String raw, int defaultValue, int min, int max)");
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

    private static Map<String, Object> curationItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("lane", "autolearn");
        item.put("entryType", "AUTOLEARN_CYCLE");
        item.put("action", "demote_heavy_work");
        item.put("reason", "gpu_hardware_pressure");
        item.put("decision", "BLOCK_RETRAIN");
        item.put("failureClass", "gpu_hardware_pressure");
        item.put("hotspot", "gpu_hardware");
        item.put("createdAt", "2026-05-30T01:10:00Z");
        item.put("datasetWriteAllowed", false);
        item.put("reviewRequired", true);
        item.put("ownerToken", "owner-token-must-not-leak");
        item.put("rawQuery", "raw query must not leak");
        item.put("payload", "payload must not leak");
        item.put("apiKey", "secret-key-must-not-leak");
        item.put("datasetPath", "C:\\private\\macmini\\train_rag_curated.jsonl");
        item.put("gpuAdmission", Map.of(
                "hardwareStatus", "degraded",
                "hardwareReason", "gpu_memory_pressure",
                "pressureLevel", "warn",
                "hardwarePressure", 0.91d,
                "retrainAllowed", false,
                "secret", "secret-key-must-not-leak"));
        item.put("counts", Map.of(
                "attempted", 3,
                "accepted", 1,
                "rawText", "raw query must not leak"));
        return item;
    }

    private static Map<String, Object> curationItemWithInvalidNumbers() {
        Map<String, Object> item = new LinkedHashMap<>(curationItem());
        item.put("gpuAdmission", Map.of(
                "hardwareStatus", "degraded",
                "hardwareReason", "gpu_memory_pressure",
                "hardwarePressure", "not-a-number",
                "retrainAllowed", false));
        item.put("counts", Map.of(
                "attempted", 3,
                "accepted", "not-a-number",
                "webReturned", Double.NaN));
        return item;
    }
}
