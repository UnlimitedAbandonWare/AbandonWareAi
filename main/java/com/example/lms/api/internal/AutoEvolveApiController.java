package com.example.lms.api.internal;

import com.example.lms.moe.RgbSoakReport;
import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.moe.RgbSoakMetrics;
import com.example.lms.scheduler.AutoEvolveDebugStore;
import com.example.lms.scheduler.AutoEvolvePreview;
import com.example.lms.scheduler.AutoEvolveRunDebug;
import com.example.lms.scheduler.TrainingJobRunner;
import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/autoevolve")
@ConditionalOnProperty(prefix = "rgb.moe", name = "enabled", havingValue = "true")
public class AutoEvolveApiController {

    private final TrainingJobRunner runner;
    private final AutoEvolveDebugStore debugStore;
    private final RgbMoeProperties moeProps;

    public AutoEvolveApiController(TrainingJobRunner runner, AutoEvolveDebugStore debugStore, RgbMoeProperties moeProps) {
        this.runner = runner;
        this.debugStore = debugStore;
        this.moeProps = moeProps;
    }

    /**
     * Manual one-shot run for ops/debug.
     */
    @PostMapping("/run")
    public ResponseEntity<RgbSoakReport> run() {
        RgbSoakReport r = runner.runOnce(false, "manual_api");
        return ResponseEntity.ok(safeReport(r));
    }

    /**
     * Lightweight "what would happen if we run now?" preview.
     *
     * <p>Useful when tuning selector policies, verifying BLUE cooldown, etc.</p>
     */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestParam(name = "requireIdle", defaultValue = "true") boolean requireIdle) {
        return ResponseEntity.ok(previewSummary(runner.preview(requireIdle)));
    }

    /**
     * Current status + last run + recent history.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam(name = "historyLimit", defaultValue = "10") int historyLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        String currentSessionId = runner.currentSessionId();
        out.put("running", runner.isRunning());
        out.put("currentSessionPresent", currentSessionId != null && !currentSessionId.isBlank());
        out.put("currentSessionHash", hashOrEmpty(currentSessionId));
        out.put("enabled", moeProps.isEnabled());

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("logPath", pathSummary(moeProps.getLogPath()));
        cfg.put("logTailLines", moeProps.getLogTailLines());
        cfg.put("soakReportDir", pathSummary(moeProps.getSoakReportDir()));
        cfg.put("idleMinMinutes", moeProps.getIdleMinMinutes());
        cfg.put("idleWindowStart", moeProps.getIdleWindowStart());
        cfg.put("idleWindowEnd", moeProps.getIdleWindowEnd());
        cfg.put("blueEnabled", moeProps.isBlueEnabled());
        cfg.put("blueMaxCallsPerRun", moeProps.getBlueMaxCallsPerRun());
        cfg.put("blueCooldownSeconds", moeProps.getBlueCooldownSeconds());
        cfg.put("debugRingSize", moeProps.getDebug() == null ? null : moeProps.getDebug().getRingSize());
        cfg.put("debugPersistEnabled", moeProps.getDebug() != null && moeProps.getDebug().isPersistEnabled());
        cfg.put("debugPersistDir", pathSummary(moeProps.getDebug() == null ? null : moeProps.getDebug().getPersistDir()));
        cfg.put("debugNdjsonFile", moeProps.getDebug() == null ? null : moeProps.getDebug().getNdjsonFileName());
        cfg.put("debugIndexFile", moeProps.getDebug() == null ? null : moeProps.getDebug().getIndexFileName());
        out.put("config", cfg);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("enabled", debugStore.isPersistEnabled());
        persist.put("dir", pathSummary(debugStore.persistDirectory()));
        persist.put("ndjsonPath", pathSummary(debugStore.ndjsonPath()));
        persist.put("indexPath", pathSummary(debugStore.indexPath()));
        out.put("debugPersistence", persist);

        out.put("historySize", debugStore.size());
        out.put("last", runSummary(debugStore.last()));
        out.put("recent", debugStore.recent(historyLimit).stream().map(AutoEvolveApiController::runSummary).toList());
        out.put("recentIndex", debugStore.recentIndex(historyLimit));

        return ResponseEntity.ok(out);
    }

    /**
     * Lightweight recent index list (newest-first).
     */
    @GetMapping("/index")
    public ResponseEntity<List<?>> index(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(debugStore.recentIndex(limit));
    }

    /**
     * Clear in-memory debug history. Does not delete persisted ndjson files.
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear() {
        debugStore.clear();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Recent run history (newest-first). Intended for debugging/ops only.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(debugStore.recent(limit).stream().map(AutoEvolveApiController::runSummary).toList());
    }

    private static Map<String, Object> pathSummary(String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", value != null && !value.isBlank());
        out.put("hash", hashOrEmpty(value));
        out.put("length", value == null ? 0 : value.length());
        return out;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static void putErrorClassSummary(Map<String, Object> out, String value) {
        out.put("errorClassPresent", value != null && !value.isBlank());
        out.put("errorClassHash", hashOrEmpty(value));
        out.put("errorClassLength", value == null ? 0 : value.length());
    }

    private static RgbSoakReport safeReport(RgbSoakReport r) {
        if (r == null) {
            return null;
        }
        return new RgbSoakReport(
                hashOrEmpty(r.sessionId()),
                r.startedAt(),
                r.endedAt(),
                SafeRedactor.traceLabelOrFallback(r.primaryStrategy(), ""),
                safeLabels(r.fallbackStrategies()),
                r.reasons(),
                hashList(r.queries()),
                safeMetrics(r.metricsByStrategy()),
                safeDebug(r.debug()));
    }

    private static List<String> safeLabels(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> SafeRedactor.traceLabelOrFallback(value, ""))
                .toList();
    }

    private static Map<String, RgbSoakMetrics> safeMetrics(Map<String, RgbSoakMetrics> metricsByStrategy) {
        if (metricsByStrategy == null || metricsByStrategy.isEmpty()) {
            return Map.of();
        }
        Map<String, RgbSoakMetrics> out = new LinkedHashMap<>();
        for (Map.Entry<String, RgbSoakMetrics> entry : metricsByStrategy.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            out.put(SafeRedactor.traceLabelOrFallback(entry.getKey(), "strategy"), entry.getValue());
        }
        return out;
    }

    private static Map<String, Object> safeDebug(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) {
            return Map.of();
        }
        Object value = SafeRedactor.diagnosticValue("autoevolve.report.debug", debug, 180);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of("summary", value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null) continue;
            out.put(SafeRedactor.traceLabelOrFallback(entry.getKey(), "field"), entry.getValue());
        }
        return out;
    }

    private static Map<String, Object> runSummary(AutoEvolveRunDebug d) {
        if (d == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionHash", SafeRedactor.hashValue(d.sessionId()));
        out.put("trigger", SafeRedactor.safeMessage(d.trigger(), 80));
        out.put("requireIdle", d.requireIdle());
        out.put("idleSatisfied", d.idleSatisfied());
        out.put("outcome", d.outcome());
        out.put("startedAt", d.startedAt());
        out.put("endedAt", d.endedAt());
        out.put("baseQueryCount", d.baseQueries() == null ? 0 : d.baseQueries().size());
        out.put("baseQueryHashes", hashList(d.baseQueries()));
        out.put("finalQueryCount", d.finalQueries() == null ? 0 : d.finalQueries().size());
        out.put("finalQueryHashes", hashList(d.finalQueries()));
        out.put("greenExpansion", d.greenExpansion());
        out.put("blueCall", blueSummary(d.blueCall()));
        out.put("reportFile", pathSummary(d.reportFile()));
        out.put("hasReport", d.report() != null);
        putErrorClassSummary(out, d.errorClass());
        out.put("errorMessage", SafeRedactor.diagnosticValue("autoevolve.run.errorMessage", d.errorMessage(), 180));
        return out;
    }

    private static Map<String, Object> previewSummary(AutoEvolvePreview p) {
        if (p == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", p.at());
        out.put("idleNow", p.idleNow());
        out.put("hasLogFeatures", p.logFeatures() != null);
        out.put("hasResourceSnapshot", p.resourceSnapshot() != null);
        out.put("primaryStrategy", p.decision() == null || p.decision().primaryStrategy() == null
                ? null : String.valueOf(p.decision().primaryStrategy()));
        out.put("baseQueryCount", p.baseQueries() == null ? 0 : p.baseQueries().size());
        out.put("baseQueryHashes", hashList(p.baseQueries()));
        out.put("willExpandWithGreen", p.willExpandWithGreen());
        out.put("willAttemptBlue", p.willAttemptBlue());
        out.put("blueBlockedReason", SafeRedactor.traceLabelOrFallback(p.blueBlockedReason(), "unknown"));
        return out;
    }

    private static Map<String, Object> blueSummary(AutoEvolveRunDebug.BlueCallDebug blue) {
        if (blue == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempted", blue.attempted());
        out.put("success", blue.success());
        out.put("latencyMs", blue.latencyMs());
        out.put("cap", blue.cap());
        out.put("outCount", blue.outCount());
        out.put("httpStatus", blue.httpStatus());
        out.put("retryAfter", SafeRedactor.safeMessage(blue.retryAfter(), 80));
        out.put("responseHeaderKeys", blue.responseHeaders() == null ? List.of() : blue.responseHeaders().keySet().stream().sorted().toList());
        putErrorClassSummary(out, blue.errorClass());
        out.put("errorMessage", SafeRedactor.diagnosticValue("autoevolve.blue.errorMessage", blue.errorMessage(), 180));
        out.put("responseBodyPreviewHash", SafeRedactor.hashValue(blue.responseBodyPreview()));
        out.put("responseBodyPreviewLength", blue.responseBodyPreview() == null ? 0 : blue.responseBodyPreview().length());
        out.put("cooldownApplied", blue.cooldownApplied());
        return out;
    }

    private static List<String> hashList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(SafeRedactor::hashValue).toList();
    }
}
