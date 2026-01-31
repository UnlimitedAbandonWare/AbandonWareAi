package com.example.lms.api.internal;

import com.example.lms.moe.RgbSoakReport;
import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.scheduler.AutoEvolveDebugStore;
import com.example.lms.scheduler.AutoEvolvePreview;
import com.example.lms.scheduler.AutoEvolveRunDebug;
import com.example.lms.scheduler.TrainingJobRunner;
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
        return ResponseEntity.ok(r);
    }

    /**
     * Lightweight "what would happen if we run now?" preview.
     *
     * <p>Useful when tuning selector policies, verifying BLUE cooldown, etc.</p>
     */
    @GetMapping("/preview")
    public ResponseEntity<AutoEvolvePreview> preview(@RequestParam(name = "requireIdle", defaultValue = "true") boolean requireIdle) {
        return ResponseEntity.ok(runner.preview(requireIdle));
    }

    /**
     * Current status + last run + recent history.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam(name = "historyLimit", defaultValue = "10") int historyLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", runner.isRunning());
        out.put("currentSessionId", runner.currentSessionId());
        out.put("enabled", moeProps.isEnabled());

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("logPath", moeProps.getLogPath());
        cfg.put("logTailLines", moeProps.getLogTailLines());
        cfg.put("soakReportDir", moeProps.getSoakReportDir());
        cfg.put("idleMinMinutes", moeProps.getIdleMinMinutes());
        cfg.put("idleWindowStart", moeProps.getIdleWindowStart());
        cfg.put("idleWindowEnd", moeProps.getIdleWindowEnd());
        cfg.put("blueEnabled", moeProps.isBlueEnabled());
        cfg.put("blueMaxCallsPerRun", moeProps.getBlueMaxCallsPerRun());
        cfg.put("blueCooldownSeconds", moeProps.getBlueCooldownSeconds());
        cfg.put("debugRingSize", moeProps.getDebug() == null ? null : moeProps.getDebug().getRingSize());
        cfg.put("debugPersistEnabled", moeProps.getDebug() != null && moeProps.getDebug().isPersistEnabled());
        cfg.put("debugPersistDir", moeProps.getDebug() == null ? null : moeProps.getDebug().getPersistDir());
        cfg.put("debugNdjsonFile", moeProps.getDebug() == null ? null : moeProps.getDebug().getNdjsonFileName());
        cfg.put("debugIndexFile", moeProps.getDebug() == null ? null : moeProps.getDebug().getIndexFileName());
        out.put("config", cfg);

        Map<String, Object> persist = new LinkedHashMap<>();
        persist.put("enabled", debugStore.isPersistEnabled());
        persist.put("dir", debugStore.persistDirectory());
        persist.put("ndjsonPath", debugStore.ndjsonPath());
        persist.put("indexPath", debugStore.indexPath());
        out.put("debugPersistence", persist);

        out.put("historySize", debugStore.size());
        out.put("last", debugStore.last());
        out.put("recent", debugStore.recent(historyLimit));
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
    public ResponseEntity<List<AutoEvolveRunDebug>> history(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ResponseEntity.ok(debugStore.recent(limit));
    }
}
