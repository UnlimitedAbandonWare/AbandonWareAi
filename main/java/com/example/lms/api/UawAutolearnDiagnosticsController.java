package com.example.lms.api;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.health.GpuGatewayDiagnostics;
import com.example.lms.health.GpuHardwareDiagnostics;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.SelfLearningBridgeDiagnostics;
import com.example.lms.uaw.autolearn.OpenCodeFreeQuotaGuard;
import com.example.lms.uaw.autolearn.UawLearningAgentHandoffWriter;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight diagnostics endpoint for the AutoLearn quantitative quality loop.
 */
@RestController
@RequestMapping("/api/diagnostics/uaw/autolearn")
public class UawAutolearnDiagnosticsController {

    private final UawAutolearnQualityTracker qualityTracker;
    private final Environment env;
    private final ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter;
    private final ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard;

    public UawAutolearnDiagnosticsController(UawAutolearnQualityTracker qualityTracker, Environment env) {
        this(qualityTracker, env, null, null);
    }

    public UawAutolearnDiagnosticsController(UawAutolearnQualityTracker qualityTracker,
                                             Environment env,
                                             ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter) {
        this(qualityTracker, env, handoffWriter, null);
    }

    @Autowired
    public UawAutolearnDiagnosticsController(UawAutolearnQualityTracker qualityTracker,
                                             Environment env,
                                             ObjectProvider<UawLearningAgentHandoffWriter> handoffWriter,
                                             ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard) {
        this.qualityTracker = qualityTracker;
        this.env = env;
        this.handoffWriter = handoffWriter;
        this.externalQuotaGuard = externalQuotaGuard;
    }

    @GetMapping(value = "/quality", produces = MediaType.APPLICATION_JSON_VALUE)
    public UawAutolearnQualityTracker.QualitySnapshot quality() {
        return qualityTracker.snapshot();
    }

    @GetMapping(value = "/loop", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> loop() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quality", qualityTracker.snapshot());
        out.put("lastLoopDiagnostics", qualityTracker.lastLoopDiagnostics());
        out.put("lastLoopHotspot", qualityTracker.lastLoopHotspot());
        out.put("nodeProfile", nodeProfile());
        out.put("externalQuota", externalQuotaStatus());
        out.put("requestThreadTrace", allowedTraceSnapshot());
        return out;
    }

    @GetMapping(value = "/gpu-gateway/preflight", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> gpuGatewayPreflight() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeProfile", nodeProfile());
        out.put("preflight", GpuGatewayDiagnostics.preflight(env));
        out.put("requestThreadTrace", allowedTraceSnapshot());
        return out;
    }

    @GetMapping(value = "/agent-handoff", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> agentHandoff() {
        return agentHandoffSummary();
    }

    private Map<String, Object> nodeProfile() {
        boolean controlPlane = bool("awx.node.control-plane",
                bool("uaw.autolearn.runtime-node.control-plane", false));
        boolean heavyAllowed = bool("awx.node.heavy-workloads-allowed",
                bool("uaw.autolearn.runtime-node.heavy-workloads-allowed", true));
        boolean autolearnEnabled = autolearnEnabled();
        boolean idleTriggerEnabled = bool("uaw.autolearn.idle-trigger.enabled", true);
        boolean retrainEnabled = bool("uaw.autolearn.retrain.enabled", true);
        boolean datasetApiEnabled = bool("uaw.dataset-api.enabled", false);
        boolean datasetApiHasKey = !ConfigValueGuards.isMissing(prop("uaw.dataset-api.key", ""));
        boolean datasetApiCurationAllowed = datasetApiEnabled && datasetApiHasKey;
        boolean learningOpsCollectorEnabled = bool("awx.learning-ops.collector.enabled", false);
        Map<String, Object> gpuGateway = GpuGatewayDiagnostics.snapshot(env);
        Map<String, Object> gpuHardware = GpuHardwareDiagnostics.snapshot(env);
        Map<String, Object> gpuAdmission = GpuHardwareDiagnostics.admissionFromSnapshot(gpuHardware);
        boolean gpuRetrainAllowed = boolValue(gpuAdmission.get("retrainAllowed"), true);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("role", firstNonBlank(prop("awx.node.role", ""), prop("uaw.autolearn.runtime-node.role", "")));
        node.put("executionNode", firstNonBlank(prop("awx.node.execution-node", ""), prop("uaw.autolearn.runtime-node.execution-node", "")));
        node.put("controlPlane", controlPlane);
        node.put("heavyWorkloadsAllowed", heavyAllowed);
        node.put("schedulingAssistant", bool("uaw.autolearn.runtime-node.scheduling-assistant", false));
        node.put("learningLoopMode", firstNonBlank(prop("awx.node.learning-loop-mode", ""), prop("uaw.autolearn.runtime-node.learning-loop-mode", "")));
        node.put("workloadPolicy", workloadPolicy(controlPlane, heavyAllowed));

        Map<String, Object> datasetApi = new LinkedHashMap<>();
        datasetApi.put("enabled", datasetApiEnabled);
        datasetApi.put("hasKey", datasetApiHasKey);
        datasetApi.put("keySource", datasetApiHasKey ? "property:uaw.dataset-api.key" : "");
        datasetApi.put("disabledReason", datasetApiDisabledReason(datasetApiEnabled, datasetApiHasKey));
        datasetApi.put("curationAllowed", datasetApiCurationAllowed);

        Map<String, Object> dataset = new LinkedHashMap<>();
        String datasetPath = resolveDatasetPath();
        dataset.put("name", firstNonBlank(prop("uaw.autolearn.dataset.name", ""), "uaw-train"));
        dataset.put("hasDatasetPath", !datasetPath.isBlank());
        putDatasetFileDiagnostics(dataset, datasetPath);
        dataset.put("datasetPathHash", hashOrEmpty(datasetPath));

        Map<String, Object> learningLoop = new LinkedHashMap<>();
        learningLoop.put("autolearnEnabled", autolearnEnabled);
        learningLoop.put("idleTriggerEnabled", idleTriggerEnabled);
        learningLoop.put("retrainEnabled", retrainEnabled);
        learningLoop.put("retrainAllowed", autolearnEnabled && retrainEnabled && heavyAllowed && gpuRetrainAllowed);
        learningLoop.put("gpuAdmissionStatus", safeMapValue(gpuAdmission, "status"));
        learningLoop.put("gpuAdmissionReason", safeMapValue(gpuAdmission, "reason"));
        learningLoop.put("datasetApiCurationAllowed", datasetApiCurationAllowed);
        learningLoop.put("learningOpsCollectorEnabled", learningOpsCollectorEnabled);
        learningLoop.put("learningOpsCollectorWritesDataset", false);
        learningLoop.put("curationAllowed", (autolearnEnabled && idleTriggerEnabled)
                || datasetApiCurationAllowed
                || learningOpsCollectorEnabled);
        learningLoop.put("failurePatternEnabled", bool("nova.orch.failure.enabled", true));
        learningLoop.put("failurePatternFeedbackEnabled", bool("nova.orch.failure.feedback.enabled", true));
        learningLoop.put("failurePatternJsonlWriteEnabled", bool("nova.orch.failure.jsonl.write-enabled", true));
        learningLoop.put("matrixTraceSurface", "requestThreadTrace.blackbox.risk.*");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("datasetApi", datasetApi);
        out.put("dataset", dataset);
        out.put("agentHandoff", agentHandoffSummary());
        out.put("externalQuota", externalQuotaStatus());
        out.put("learningLoop", learningLoop);
        out.put("gpuGateway", gpuGateway);
        out.put("gpuHardware", gpuHardware);
        out.put("selfLearningBridge", SelfLearningBridgeDiagnostics.snapshot(env, gpuGateway, gpuHardware));
        return out;
    }

    private static Map<String, Object> allowedTraceSnapshot() {
        Map<String, Object> trace = TraceStore.getAll();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : trace.entrySet()) {
            String key = entry.getKey();
            if (isAllowedTraceKey(key)) {
                if ("uaw.dataset-api.datasetFile".equals(key)) {
                    putDatasetFileTraceDiagnostics(out, "uaw.dataset-api.", entry.getValue());
                    continue;
                }
                out.put(key, SafeRedactor.diagnosticValue(key, entry.getValue()));
            }
        }
        return out;
    }

    private static boolean isAllowedTraceKey(String key) {
        return key != null && (key.startsWith("learning.loop.")
                || key.startsWith("learning.validation.")
                || key.startsWith("blackbox.risk.")
                || key.startsWith("uaw.retrain.")
                || key.startsWith("uaw.dataset-api.")
                || key.startsWith("uaw.agent.handoff.")
                || key.startsWith("uaw.autolearn.externalQuota")
                || key.startsWith("uaw.gpu-gateway.")
                || key.startsWith("uaw.gpu-hardware.")
                || key.startsWith("awx.learning-ops.collector.")
                || "learning.error.hotspot".equals(key)
                || "uaw.idle.loop-diagnostics".equals(key)
                || "uaw.autolearn.loop.hotspot".equals(key));
    }

    private static void putDatasetFileTraceDiagnostics(Map<String, Object> out, String prefix, Object rawFileName) {
        String fileName = rawFileName == null ? "" : String.valueOf(rawFileName).trim();
        out.put(prefix + "datasetFileHash", fileName.isBlank() ? "" : hashOrEmpty(fileName));
        out.put(prefix + "datasetFileLength", fileName.length());
    }

    private boolean autolearnEnabled() {
        String uaw = prop("uaw.autolearn.enabled", null);
        if (uaw != null) {
            return Boolean.parseBoolean(uaw);
        }
        String legacy = prop("autolearn.enabled", null);
        if (legacy != null) {
            return Boolean.parseBoolean(legacy);
        }
        String trainIdle = prop("train_idle.enabled", null);
        if (trainIdle != null) {
            return Boolean.parseBoolean(trainIdle);
        }
        return true;
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

    private String resolveDatasetPath() {
        return firstNonBlank(
                prop("uaw.autolearn.dataset.path", ""),
                prop("autolearn.dataset.path", ""),
                prop("dataset.train-file-path", ""),
                "data/train_rag.jsonl");
    }

    private Map<String, Object> agentHandoffSummary() {
        UawLearningAgentHandoffWriter writer = handoffWriter == null ? null : handoffWriter.getIfAvailable();
        if (writer != null) {
            return writer.manifestSummary();
        }
        boolean enabled = bool("uaw.autolearn.agent-handoff.enabled", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("enabled", enabled);
        out.put("disabledReason", enabled ? "writer_unavailable" : "disabled_by_config");
        String rootPath = prop("uaw.autolearn.agent-handoff.root-path", "data/agent-handoff/codex");
        putFileNameDiagnostics(out, "rootDir", rootPath);
        out.put("rootPathHash", hashOrEmpty(rootPath));
        out.put("writeFullAcceptedText", bool("uaw.autolearn.agent-handoff.write-full-accepted-text", false));
        out.put("maxLineBytes", intProp("uaw.autolearn.agent-handoff.max-line-bytes", 32768));
        out.put("files", Map.of());
        return out;
    }

    private Map<String, Object> externalQuotaStatus() {
        OpenCodeFreeQuotaGuard guard = externalQuotaGuard == null ? null : externalQuotaGuard.getIfAvailable();
        if (guard != null) {
            return guard.status();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", bool("uaw.autolearn.external-quota.enabled", false));
        out.put("applies", false);
        out.put("routeModel", prop("uaw.autolearn.external-quota.route-model", "llmrouter.external"));
        out.put("providerHost", prop("uaw.autolearn.external-quota.provider-host", "opencode.ai"));
        out.put("freeModel", prop("uaw.autolearn.external-quota.free-model", "deepseek-v4-flash-free"));
        out.put("resetZone", prop("uaw.autolearn.external-quota.reset-zone", "UTC"));
        out.put("privacyMode", prop("uaw.autolearn.external-quota.privacy-mode", "STATIC_SYNTHETIC_ONLY"));
        out.put("canonicalTrainingPolicy", prop("uaw.autolearn.external-quota.canonical-training-policy",
                "EXTERNAL_FREE_CURATE_ONLY"));
        out.put("endpointHost", "");
        out.put("model", "");
        out.put("routeEnabled", false);
        out.put("routeConfigured", false);
        out.put("endpointFamily", "UNKNOWN");
        out.put("modelPolicy", "DISABLED");
        out.put("hasKey", false);
        out.put("consumed", false);
        out.put("nextReservationTokens", intProp("uaw.autolearn.external-quota.max-output-tokens-per-call", 512));
        out.put("disabledReason", "guard_unavailable");
        out.put("allowed", false);
        return out;
    }

    private static String datasetApiDisabledReason(boolean enabled, boolean hasKey) {
        if (!enabled) {
            return "disabled_by_config";
        }
        return hasKey ? null : "missing_internal_key";
    }

    private static String workloadPolicy(boolean controlPlane, boolean heavyAllowed) {
        if (controlPlane && !heavyAllowed) {
            return "control_plane_curate_observe_schedule";
        }
        if (heavyAllowed) {
            return "gpu_executor_heavy_workloads";
        }
        return "observer_only";
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

    private static String datasetFileName(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }

    private static void putDatasetFileDiagnostics(Map<String, Object> out, String path) {
        String fileName = datasetFileName(path);
        out.put("datasetFileHash", fileName.isBlank() ? "" : hashOrEmpty(fileName));
        out.put("datasetFileLength", fileName.length());
    }

    private static void putFileNameDiagnostics(Map<String, Object> out, String prefix, String path) {
        String fileName = datasetFileName(path);
        out.put(prefix + "Hash", fileName.isBlank() ? "" : hashOrEmpty(fileName));
        out.put(prefix + "Length", fileName.length());
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private int intProp(String key, int defaultValue) {
        String value = prop(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            traceSuppressed("uawDiagnostics.intProp", ignored);
            return defaultValue;
        }
    }

    private double doubleProp(String key, double defaultValue) {
        String value = prop(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            traceSuppressed("uawDiagnostics.doubleProp", ignored);
            return defaultValue;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("uaw.diagnostics.suppressed.stage", safeStage);
        TraceStore.put("uaw.diagnostics.suppressed." + safeStage, true);
        TraceStore.put("uaw.diagnostics.suppressed.errorType", safeErrorType);
        TraceStore.put("uaw.diagnostics.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(RuntimeException failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? defaultValue : Boolean.parseBoolean(s);
    }

    private static String safeMapValue(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
