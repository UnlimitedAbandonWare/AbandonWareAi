package com.example.lms.uaw.autolearn;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact, redacted labels that connect runtime observations to the existing
 * Plan DSL, IdleTrain, Failure Pattern, 9-Matrix, and Dataset Pipeline loops.
 */
public final class SelfLearningBridgeDiagnostics {

    private SelfLearningBridgeDiagnostics() {
    }

    public static Map<String, Object> snapshot(Environment env, Map<String, Object> gpuGateway) {
        return snapshot(env, gpuGateway, Map.of());
    }

    public static Map<String, Object> snapshot(Environment env,
                                               Map<String, Object> gpuGateway,
                                               Map<String, Object> gpuHardware) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planDsl", planDsl(env));
        out.put("idleTrain", idleTrain(env));
        out.put("failurePattern", failurePattern(env));
        out.put("matrix9", matrix9());
        out.put("datasetPipeline", datasetPipeline(env));
        out.put("gpuGateway", compactGpuGateway(gpuGateway));
        out.put("gpuHardware", compactGpuHardware(gpuHardware));
        return out;
    }

    public static Map<String, Object> compactGpuGateway(Map<String, Object> gpuGateway) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (gpuGateway == null || gpuGateway.isEmpty()) {
            out.put("enabled", false);
            out.put("routePolicy", "unknown");
            out.put("disabledReason", "missing_gateway_snapshot");
            return out;
        }
        out.put("enabled", gpuGateway.getOrDefault("enabled", false));
        out.put("mode", safeLabel(gpuGateway.get("mode")));
        out.put("routePolicy", safeLabel(gpuGateway.get("routePolicy")));
        out.put("targetExecutionNode", safeLabel(gpuGateway.get("targetExecutionNode")));
        out.put("disabledReason", safeNullableLabel(gpuGateway.get("disabledReason")));
        out.put("primaryStatus", endpointStatus(gpuGateway, "primaryChat"));
        out.put("fastStatus", endpointStatus(gpuGateway, "fastHelper"));
        out.put("embeddingStatus", endpointStatus(gpuGateway, "embedding"));
        return out;
    }

    public static Map<String, Object> compactGpuHardware(Map<String, Object> gpuHardware) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (gpuHardware == null || gpuHardware.isEmpty()) {
            out.put("available", false);
            out.put("status", "missing_hardware_snapshot");
            out.put("detectedCount", 0);
            out.put("heavyLaneReady", false);
            return out;
        }
        out.put("enabled", gpuHardware.getOrDefault("enabled", false));
        out.put("available", gpuHardware.getOrDefault("available", false));
        out.put("status", safeLabel(gpuHardware.get("status")));
        out.put("detectedCount", gpuHardware.getOrDefault("detectedCount", 0));
        out.put("hasRtx3090", gpuHardware.getOrDefault("hasRtx3090", false));
        out.put("hasRtx3060", gpuHardware.getOrDefault("hasRtx3060", false));
        out.put("heavyLaneReady", gpuHardware.getOrDefault("heavyLaneReady", false));
        out.put("maxMemoryUsedRatio", gpuHardware.getOrDefault("maxMemoryUsedRatio", 0.0d));
        Object admission = gpuHardware.get("admission");
        if (admission instanceof Map<?, ?> admissionMap) {
            out.put("admissionStatus", safeLabel(admissionMap.get("status")));
            out.put("admissionReason", safeLabel(admissionMap.get("reason")));
            out.put("pressureLevel", safeLabel(admissionMap.get("pressureLevel")));
            out.put("retrainAllowed", admissionMap.containsKey("retrainAllowed")
                    ? admissionMap.get("retrainAllowed") : true);
            out.put("rerankAllowed", admissionMap.containsKey("rerankAllowed")
                    ? admissionMap.get("rerankAllowed") : true);
            out.put("embeddingFallbackAllowed", admissionMap.containsKey("embeddingFallbackAllowed")
                    ? admissionMap.get("embeddingFallbackAllowed") : true);
        }
        return out;
    }

    private static Map<String, Object> planDsl(Environment env) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "Plan DSL");
        out.put("planId", firstNonBlank(
                traceString("plan.id"),
                traceString("retrieval.plan"),
                traceString("plan.source"),
                prop(env, "nova.default-plan-id", ""),
                prop(env, "spring.ai.nova.default-plan-id", ""),
                "safe_autorun.v1"));
        out.put("resourceRoot", "classpath:/plans");
        out.put("status", "observe_existing_plan_labels");
        return out;
    }

    private static Map<String, Object> idleTrain(Environment env) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "IdleTrain");
        out.put("tracePrefix", "uaw.idle.*");
        out.put("enabled", bool(env, "uaw.autolearn.idle-trigger.enabled", true));
        out.put("mode", firstNonBlank(
                prop(env, "awx.node.learning-loop-mode", ""),
                prop(env, "uaw.autolearn.runtime-node.learning-loop-mode", ""),
                "curate-observe-schedule"));
        out.put("status", "reuse_idle_trace_as_training_signal");
        return out;
    }

    private static Map<String, Object> failurePattern(Environment env) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "Failure Pattern");
        out.put("enabled", bool(env, "nova.orch.failure.enabled", true));
        out.put("feedbackEnabled", bool(env, "nova.orch.failure.feedback.enabled", true));
        out.put("jsonlWriteEnabled", bool(env, "nova.orch.failure.jsonl.write-enabled", true));
        out.put("tracePrefix", "uaw.autolearn.loop.hotspot");
        out.put("status", "demote_failures_to_patterns");
        return out;
    }

    private static Map<String, Object> matrix9() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "9-Matrix");
        out.put("tracePrefix", "blackbox.risk.*");
        out.put("matrixTraceSurface", "requestThreadTrace.blackbox.risk.*");
        out.put("status", "observe_matrix_trace_labels");
        return out;
    }

    private static Map<String, Object> datasetPipeline(Environment env) {
        String datasetPath = firstNonBlankRaw(
                prop(env, "uaw.autolearn.dataset.path", ""),
                prop(env, "autolearn.dataset.path", ""),
                prop(env, "dataset.train-file-path", ""));
        boolean datasetApiEnabled = bool(env, "uaw.dataset-api.enabled", false);
        boolean datasetApiHasKey = !ConfigValueGuards.isMissing(prop(env, "uaw.dataset-api.key", ""));
        boolean datasetApiCurationAllowed = datasetApiEnabled && datasetApiHasKey;
        boolean idleTrainCurationAllowed = autolearnEnabled(env)
                && bool(env, "uaw.autolearn.idle-trigger.enabled", true);
        boolean collectorEnabled = bool(env, "awx.learning-ops.collector.enabled", false);
        String collectorOutputPath = firstNonBlankRaw(
                prop(env, "awx.learning-ops.collector.output-path", ""),
                "data/macmini/learning-ops-curation.jsonl");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "Dataset Pipeline");
        out.put("datasetName", firstNonBlank(prop(env, "uaw.autolearn.dataset.name", ""), "uaw-train"));
        out.put("hasDatasetPath", !ConfigValueGuards.isMissing(datasetPath));
        String datasetFile = datasetFileName(datasetPath);
        out.put("datasetFileHash", datasetFile.isBlank() ? "" : hashOrEmpty(datasetFile));
        out.put("datasetFileLength", datasetFile.length());
        out.put("datasetPathHash", hashOrEmpty(datasetPath));
        out.put("datasetApiEnabled", datasetApiEnabled);
        out.put("datasetApiHasKey", datasetApiHasKey);
        out.put("datasetApiCurationAllowed", datasetApiCurationAllowed);
        out.put("idleTrainCurationAllowed", idleTrainCurationAllowed);
        out.put("collectorEnabled", collectorEnabled);
        out.put("collectorMode", "read_only_curation_candidates");
        out.put("collectorWritesDataset", false);
        out.put("collectorRequiresReview", true);
        String collectorOutputFile = datasetFileName(collectorOutputPath);
        out.put("collectorOutputFileHash", collectorOutputFile.isBlank() ? "" : hashOrEmpty(collectorOutputFile));
        out.put("collectorOutputFileLength", collectorOutputFile.length());
        out.put("collectorOutputPathHash", hashOrEmpty(collectorOutputPath));
        out.put("collectorStatus", collectorEnabled ? "ready_read_only_curation_queue" : "disabled_by_config");
        out.put("retrainTracePrefix", "uaw.retrain.*");
        out.put("status", datasetPipelineStatus(datasetApiEnabled, datasetApiHasKey,
                datasetApiCurationAllowed, idleTrainCurationAllowed, collectorEnabled));
        return out;
    }

    private static boolean autolearnEnabled(Environment env) {
        String uaw = prop(env, "uaw.autolearn.enabled", null);
        if (uaw != null) {
            return Boolean.parseBoolean(uaw);
        }
        String legacy = prop(env, "autolearn.enabled", null);
        if (legacy != null) {
            return Boolean.parseBoolean(legacy);
        }
        String trainIdle = prop(env, "train_idle.enabled", null);
        if (trainIdle != null) {
            return Boolean.parseBoolean(trainIdle);
        }
        return true;
    }

    private static String datasetPipelineStatus(boolean datasetApiEnabled,
                                                boolean datasetApiHasKey,
                                                boolean datasetApiCurationAllowed,
                                                boolean idleTrainCurationAllowed,
                                                boolean collectorEnabled) {
        if (datasetApiCurationAllowed) {
            return "curate_via_dataset_api";
        }
        if (idleTrainCurationAllowed) {
            return "curate_via_idle_train";
        }
        if (datasetApiEnabled && !datasetApiHasKey) {
            return "missing_internal_key";
        }
        if (collectorEnabled) {
            return "read_only_curation_queue";
        }
        return "disabled_by_config";
    }

    @SuppressWarnings("unchecked")
    private static String endpointStatus(Map<String, Object> gpuGateway, String endpointName) {
        Object endpoints = gpuGateway.get("endpoints");
        if (endpoints instanceof Map<?, ?> endpointMap) {
            Object endpoint = endpointMap.get(endpointName);
            if (endpoint instanceof Map<?, ?> map) {
                return safeLabel(map.get("status"));
            }
        }
        return "missing_endpoint_snapshot";
    }

    private static boolean bool(Environment env, String key, boolean defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String prop(Environment env, String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    private static String traceString(String key) {
        Object value = TraceStore.get(key);
        return value == null ? "" : safeLabel(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return safeLabel(value);
            }
        }
        return "";
    }

    private static String firstNonBlankRaw(String... values) {
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

    private static String datasetFileName(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String safeNullableLabel(Object value) {
        return value == null ? null : safeLabel(value);
    }

    private static String safeLabel(Object value) {
        if (value == null) {
            return "";
        }
        String masked = SafeRedactor.redact(String.valueOf(value));
        if (masked == null) {
            return "";
        }
        String label = masked.replace('\n', ' ').replace('\r', ' ').trim()
                .replaceAll("[^A-Za-z0-9_.:-]", "_");
        return label.length() <= 96 ? label : label.substring(0, 96);
    }
}
