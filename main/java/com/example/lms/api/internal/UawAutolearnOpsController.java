package com.example.lms.api.internal;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.AutoLearnCycleResult;
import com.example.lms.uaw.autolearn.OpenCodeFreeQuotaGuard;
import com.example.lms.uaw.autolearn.PreemptionToken;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawAutolearnService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/autolearn")
@ConditionalOnProperty(prefix = "uaw.autolearn.ops", name = "enabled", havingValue = "true", matchIfMissing = false)
public class UawAutolearnOpsController {

    private static final System.Logger LOG = System.getLogger(UawAutolearnOpsController.class.getName());

    private final Environment env;
    private final UawAutolearnProperties props;
    private final UawAutolearnService autolearnService;
    private final UawAutolearnQualityTracker qualityTracker;
    private final ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard;

    public UawAutolearnOpsController(Environment env,
                                     UawAutolearnProperties props,
                                     UawAutolearnService autolearnService,
                                     UawAutolearnQualityTracker qualityTracker) {
        this(env, props, autolearnService, qualityTracker, null);
    }

    @Autowired
    public UawAutolearnOpsController(Environment env,
                                     UawAutolearnProperties props,
                                     UawAutolearnService autolearnService,
                                     UawAutolearnQualityTracker qualityTracker,
                                     ObjectProvider<OpenCodeFreeQuotaGuard> externalQuotaGuard) {
        this.env = env;
        this.props = props;
        this.autolearnService = autolearnService;
        this.qualityTracker = qualityTracker;
        this.externalQuotaGuard = externalQuotaGuard;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", bool("uaw.autolearn.enabled", false));
        out.put("opsEnabled", bool("uaw.autolearn.ops.enabled", false));
        out.put("idleTriggerEnabled", bool("uaw.autolearn.idle-trigger.enabled", false));
        out.put("retrainEnabled", bool("uaw.autolearn.retrain.enabled", true));
        putDatasetDiagnostics(out, resolveDatasetPath());
        out.put("quality", qualityTracker.snapshot());
        out.put("lastLoopDiagnostics", qualityTracker.lastLoopDiagnostics());
        out.put("lastLoopHotspot", qualityTracker.lastLoopHotspot());
        out.put("externalQuota", externalQuotaStatus());
        return out;
    }

    @PostMapping(value = "/run-once", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> runOnce() {
        String sessionId = "uaw-manual-" + System.currentTimeMillis();
        String datasetPath = resolveDatasetPath();
        long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, props.getMaxCycleSeconds())).toNanos();
        PreemptionToken token = () -> false;
        AutoLearnCycleResult result = autolearnService.runCycle(new File(datasetPath), sessionId, token, deadline);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionHash", SafeRedactor.hashValue(sessionId));
        out.put("attempted", result.attempted());
        out.put("acceptedCount", result.acceptedCount());
        out.put("abortedByUser", result.abortedByUser());
        out.put("trainAllowed", result.trainAllowed());
        out.put("trainDecision", result.trainDecision());
        out.put("topProblem", result.topProblem());
        putDatasetDiagnostics(out, result.datasetPath());
        return out;
    }

    private String resolveDatasetPath() {
        String p = prop("uaw.autolearn.dataset.path", "");
        if (p.isBlank()) p = prop("autolearn.dataset.path", "");
        if (p.isBlank()) p = prop("dataset.train-file-path", "");
        return p.isBlank() ? "data/train_rag.jsonl" : p;
    }

    private void putDatasetDiagnostics(Map<String, Object> out, String datasetPath) {
        String path = datasetPath == null ? "" : datasetPath.trim();
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String fileName = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        out.put("datasetFileHash", fileName.isBlank() ? "" : SafeRedactor.hashValue(fileName));
        out.put("datasetFileLength", fileName.length());
        out.put("datasetPathHash", path.isBlank() ? "" : SafeRedactor.hashValue(path));
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

    private boolean bool(String key, boolean defaultValue) {
        String value = prop(key, null);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private String prop(String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    private int intProp(String key, int defaultValue) {
        String value = prop(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            traceSuppressed("uawOps.intProp", ignored);
            return defaultValue;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "UAW AutoLearn ops fallback stage={0} errorType={1}",
                    stage,
                    errorType(failure));
        }
    }

    private static String errorType(RuntimeException failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }
}
