package com.example.lms.learning.ops;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class RagLearningOpsCurationCollector {

    private static final Logger log = LoggerFactory.getLogger(RagLearningOpsCurationCollector.class);

    private static final String CHECKPOINT = "[AWX][learning-ops][collector]";
    private static final String TRACE_PREFIX = "awx.learning-ops.collector.";
    private static final int DEFAULT_MAX_ITEMS = 20;
    private static final int DEFAULT_OVERVIEW_LIMIT = 50;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_OVERVIEW_LIMIT = 200;

    private final RagLearningOpsDashboardService dashboardService;
    private final ObjectMapper objectMapper;
    private final Environment env;

    public RagLearningOpsCurationCollector(RagLearningOpsDashboardService dashboardService,
                                           ObjectMapper objectMapper,
                                           Environment env) {
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.env = env;
    }

    @Scheduled(
            fixedDelayString = "${awx.learning-ops.collector.interval-ms:300000}",
            initialDelayString = "${awx.learning-ops.collector.initial-delay-ms:60000}"
    )
    public void collectScheduled() {
        if (!enabled()) {
            return;
        }
        try {
            collectOnce("scheduled");
        } catch (Exception e) {
            publishTrace("error", 0, null, e.getClass().getSimpleName());
            log.warn("{} status=error errorClass={}", CHECKPOINT, e.getClass().getSimpleName());
        }
    }

    public Map<String, Object> collectOnce(String reason) {
        Map<String, Object> status = status("starting", reason);
        if (!enabled()) {
            status.put("status", "disabled_by_config");
            publishTrace("disabled_by_config", 0, null, "");
            return status;
        }
        if (dashboardService == null) {
            status.put("status", "dashboard_unavailable");
            publishTrace("dashboard_unavailable", 0, null, "");
            return status;
        }

        Map<String, Object> overview = dashboardService.overview(overviewLimit());
        Map<String, Object> queue = datasetPipelineQueue(overview);
        List<Map<String, Object>> items = compactItems(queue.get("items"), maxItems());
        status.put("queueStatus", safeScalar(queue.get("status"), 48));
        status.put("itemCount", items.size());
        if (items.isEmpty()) {
            status.put("status", "no_candidates");
            publishTrace("no_candidates", 0, null, "");
            return status;
        }

        Path output = outputPath();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", Instant.now().toString());
        record.put("checkpoint", CHECKPOINT);
        record.put("reason", safeScalar(reason, 64));
        record.put("nodeRole", safeScalar(prop("awx.node.role", "unknown"), 64));
        record.put("executionNode", safeScalar(prop("awx.node.execution-node", "unknown"), 96));
        record.put("learningLoopMode", safeScalar(prop("awx.node.learning-loop-mode", "curate-observe-schedule"), 96));
        record.put("queueStatus", status.get("queueStatus"));
        record.put("itemCount", items.size());
        record.put("writesDataset", false);
        record.put("requiresReview", true);
        record.put("items", items);

        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output,
                    objectMapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            status.put("status", "written");
            putOutputDiagnostics(status, output);
            publishTrace("written", items.size(), output, "");
            return status;
        } catch (Exception e) {
            status.put("status", "write_failed");
            putOutputDiagnostics(status, output);
            status.put("errorClass", e.getClass().getSimpleName());
            publishTrace("write_failed", items.size(), output, e.getClass().getSimpleName());
            log.warn("{} status=write_failed errorClass={}", CHECKPOINT, e.getClass().getSimpleName());
            return status;
        }
    }

    private Map<String, Object> status(String status, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpoint", CHECKPOINT);
        out.put("enabled", enabled());
        out.put("status", status);
        out.put("reason", safeScalar(reason, 64));
        out.put("writesDataset", false);
        out.put("requiresReview", true);
        return out;
    }

    private static Map<String, Object> datasetPipelineQueue(Map<String, Object> overview) {
        Object opsLedger = overview == null ? null : overview.get("opsLedger");
        if (opsLedger instanceof Map<?, ?> ledgerMap) {
            Object queue = ledgerMap.get("datasetPipelineQueue");
            if (queue instanceof Map<?, ?> queueMap) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : queueMap.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        }
        return Map.of("status", "queue_missing", "items", List.of());
    }

    private static List<Map<String, Object>> compactItems(Object value, int limit) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> compact = compactItem(map);
            if (!compact.isEmpty()) {
                out.add(compact);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> compactItem(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyScalar(out, source, "lane", 32);
        copyScalar(out, source, "entryType", 48);
        copyScalar(out, source, "action", 64);
        copyScalar(out, source, "reason", 96);
        copyScalar(out, source, "decision", 64);
        copyScalar(out, source, "failureClass", 96);
        copyScalar(out, source, "hotspot", 96);
        copyScalar(out, source, "createdAt", 64);
        copyScalar(out, source, "planId", 96);
        copyScalar(out, source, "strategyName", 96);
        copyScalar(out, source, "resourceTier", 48);
        copyBoolean(out, source, "datasetWriteAllowed");
        copyBoolean(out, source, "reviewRequired");
        Object gpuAdmission = source.get("gpuAdmission");
        if (gpuAdmission instanceof Map<?, ?> map) {
            Map<String, Object> compactGpu = compactGpuAdmission(map);
            if (!compactGpu.isEmpty()) {
                out.put("gpuAdmission", compactGpu);
            }
        }
        Object counts = source.get("counts");
        if (counts instanceof Map<?, ?> map) {
            Map<String, Object> compactCounts = compactNumericMap(map);
            if (!compactCounts.isEmpty()) {
                out.put("counts", compactCounts);
            }
        }
        if (!out.containsKey("action")) {
            return Map.of();
        }
        out.put("writesDataset", false);
        out.put("requiresReview", true);
        return out;
    }

    private static Map<String, Object> compactGpuAdmission(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyScalar(out, source, "hardwareStatus", 48);
        copyScalar(out, source, "hardwareReason", 96);
        copyScalar(out, source, "pressureLevel", 48);
        copyNumber(out, source, "hardwarePressure");
        copyBoolean(out, source, "retrainAllowed");
        copyBoolean(out, source, "rerankAllowed");
        copyBoolean(out, source, "embeddingFallbackAllowed");
        copyScalar(out, source, "gatewayStatus", 48);
        copyNumber(out, source, "gatewayPressure");
        return out;
    }

    private static Map<String, Object> compactNumericMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("attempted", "accepted", "resultCount", "webAfterFilter", "webReturned")) {
            copyNumber(out, source, key);
        }
        return out;
    }

    private static void copyScalar(Map<String, Object> out, Map<?, ?> source, String key, int max) {
        Object value = source.get(key);
        String safe = safeScalar(value, max);
        if (!safe.isBlank()) {
            out.put(key, safe);
        }
    }

    private static void copyBoolean(Map<String, Object> out, Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean b) {
            out.put(key, b);
            return;
        }
        if (value instanceof CharSequence seq) {
            String s = seq.toString().trim();
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                out.put(key, Boolean.parseBoolean(s));
            }
        }
    }

    private static void copyNumber(Map<String, Object> out, Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceSuppressed("copyNumber", new NumberFormatException("non-finite"));
                return;
            }
            out.put(key, round4(parsed));
            return;
        }
        if (value != null) {
            try {
                double parsed = Double.parseDouble(String.valueOf(value).trim());
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                out.put(key, round4(parsed));
            } catch (NumberFormatException ignore) {
                traceSuppressed("copyNumber", ignore);
                // Non-numeric values stay out of the public collector record.
            }
        }
    }

    private void publishTrace(String status, int itemCount, Path output, String errorClass) {
        TraceStore.put(TRACE_PREFIX + "status", status);
        TraceStore.put(TRACE_PREFIX + "itemCount", itemCount);
        TraceStore.put(TRACE_PREFIX + "writesDataset", false);
        TraceStore.put(TRACE_PREFIX + "requiresReview", true);
        if (output != null) {
            TraceStore.put(TRACE_PREFIX + "outputFileHash", fileHash(output));
            TraceStore.put(TRACE_PREFIX + "outputFileLength", fileLength(output));
            TraceStore.put(TRACE_PREFIX + "outputPathHash", hashPath(output));
        }
        if (errorClass != null && !errorClass.isBlank()) {
            TraceStore.put(TRACE_PREFIX + "errorClass", safeScalar(errorClass, 64));
        }
    }

    private static void putOutputDiagnostics(Map<String, Object> status, Path output) {
        if (status == null || output == null) {
            return;
        }
        status.put("outputFileHash", fileHash(output));
        status.put("outputFileLength", fileLength(output));
        status.put("outputPathHash", hashPath(output));
    }

    private boolean enabled() {
        return bool("awx.learning-ops.collector.enabled", false);
    }

    private int maxItems() {
        return clampInt(prop("awx.learning-ops.collector.max-items", ""), DEFAULT_MAX_ITEMS, 1, MAX_ITEMS);
    }

    private int overviewLimit() {
        return clampInt(prop("awx.learning-ops.collector.max-overview-limit", ""), DEFAULT_OVERVIEW_LIMIT, 1, MAX_OVERVIEW_LIMIT);
    }

    private Path outputPath() {
        return Path.of(firstNonBlank(
                prop("awx.learning-ops.collector.output-path", ""),
                "data/macmini/learning-ops-curation.jsonl"));
    }

    private boolean bool(String key, boolean defaultValue) {
        String value = prop(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private String prop(String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    private static int clampInt(String raw, int defaultValue, int min, int max) {
        try {
            int parsed = raw == null || raw.isBlank() ? defaultValue : Integer.parseInt(raw.trim());
            return Math.min(Math.max(parsed, min), max);
        } catch (NumberFormatException ignore) {
            traceSuppressed("clampInt", ignore);
            return defaultValue;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put(TRACE_PREFIX + "suppressed." + safeStage, true);
        TraceStore.put(TRACE_PREFIX + "suppressed." + safeStage + ".errorType", "invalid_number");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safeScalar(Object value, int max) {
        if (value == null) {
            return "";
        }
        String masked = SafeRedactor.redact(String.valueOf(value));
        if (masked == null) {
            return "";
        }
        String oneLine = masked.replace('\n', ' ').replace('\r', ' ').trim();
        int safeMax = Math.max(8, max);
        return oneLine.length() <= safeMax ? oneLine : oneLine.substring(0, safeMax);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : safeScalar(path.getFileName().toString(), 160);
    }

    private static String fileHash(Path path) {
        String name = fileName(path);
        return name.isBlank() ? "" : Objects.toString(SafeRedactor.hashValue(name), "");
    }

    private static int fileLength(Path path) {
        String name = fileName(path);
        return name.length();
    }

    private static String hashPath(Path path) {
        return path == null ? "" : Objects.toString(SafeRedactor.hashValue(path.toString()), "");
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
