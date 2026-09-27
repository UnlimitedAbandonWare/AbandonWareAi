package com.example.lms.api.internal;

import com.example.lms.moe.RgbSoakReport;
import com.example.lms.moe.RgbSoakMetrics;
import com.example.lms.scheduler.TrainingJobRunner;
import com.example.lms.service.soak.SoakQuickReport;
import com.example.lms.service.soak.SoakReport;
import com.example.lms.service.soak.SoakTestService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/soak")
@ConditionalOnProperty(prefix = "soak", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SoakApiController {
    private static final System.Logger LOG = System.getLogger(SoakApiController.class.getName());
    private static final int MAX_TOP_K = 100;

    private final SoakTestService service;
    private final ObjectProvider<TrainingJobRunner> autoEvolveRunner;

    public SoakApiController(SoakTestService service,
                             ObjectProvider<TrainingJobRunner> autoEvolveRunner) {
        this.service = service;
        this.autoEvolveRunner = autoEvolveRunner;
    }

    @GetMapping("/run")
    public ResponseEntity<SoakReport> run(@RequestParam(defaultValue = "10") int k,
                                          @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.run(normalizeTopK(k), topic));
    }

    /**
     * Quick soak endpoint with a fixed schema JSON response.
     *
     * Example:
     * /internal/soak/quick?topic=naver-fixed10&k=10
     *
     * Backward compatibility:
     * - topic=naver-bing-fixed10 is treated as an alias of naver-fixed10.
     */
    @GetMapping("/quick")
    public ResponseEntity<SoakQuickReport> quick(@RequestParam(defaultValue = "10") int k,
                                                 @RequestParam(defaultValue = "all") String topic) {
        return ResponseEntity.ok(service.runQuick(normalizeTopK(k), topic));
    }

    private static int normalizeTopK(int requested) {
        return Math.max(1, Math.min(requested, MAX_TOP_K));
    }

    /**
     * RGB quick report (offline-only). This calls TrainingJobRunner once and writes a report file.
     */
    @GetMapping("/rgb")
    public ResponseEntity<?> rgb() {
        TrainingJobRunner runner = autoEvolveRunner.getIfAvailable();
        if (runner == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "ok", false,
                    "error", "training_job_runner_unavailable"));
        }
        try {
            RgbSoakReport r = runner.runOnce(false, "soak_api");
            if (r == null) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.ok(safeRgbReport(r));
        } catch (Exception e) {
            traceSuppressed("soak.rgb", e);
            return ResponseEntity.status(500).body(publicRgbSoakError(e));
        }
    }

    private static RgbSoakReport safeRgbReport(RgbSoakReport r) {
        if (r == null) return null;
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
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .map(value -> SafeRedactor.traceLabelOrFallback(value, ""))
                .toList();
    }

    private static List<String> hashList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(SoakApiController::hashOrEmpty).toList();
    }

    private static Map<String, RgbSoakMetrics> safeMetrics(Map<String, RgbSoakMetrics> metricsByStrategy) {
        if (metricsByStrategy == null || metricsByStrategy.isEmpty()) return Map.of();
        Map<String, RgbSoakMetrics> out = new LinkedHashMap<>();
        for (Map.Entry<String, RgbSoakMetrics> entry : metricsByStrategy.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            out.put(SafeRedactor.traceLabelOrFallback(entry.getKey(), "strategy"), entry.getValue());
        }
        return out;
    }

    private static Map<String, Object> safeDebug(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) return Map.of();
        Object value = SafeRedactor.diagnosticValue("soak.rgb.report.debug", debug, 180);
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

    static String publicRgbSoakError(Exception e) {
        String message = e == null ? "" : String.valueOf(e.getMessage());
        return "rgb soak failed: errorCode=rgb_soak_failed"
                + " errorHash=" + SafeRedactor.hashValue(message)
                + " errorLength=" + message.length();
    }

    private static void traceSuppressed(String stage, Exception failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Soak API fallback stage={0} errorType={1}",
                    stage,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
        }
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }
}
