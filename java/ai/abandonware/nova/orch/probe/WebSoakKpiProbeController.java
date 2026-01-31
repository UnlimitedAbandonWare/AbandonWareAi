package ai.abandonware.nova.orch.probe;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal WebSoak KPI probe (Nova Overlay).
 *
 * - POST /internal/probe/websoak-kpi/run
 * - Enabled by: probe.websoak-kpi.enabled=true
 * - Optional auth: probe.websoak-kpi.key + header X-Internal-Key
 */
@Slf4j
@RestController
@RequestMapping("/internal/probe/websoak-kpi")
public class WebSoakKpiProbeController {

    private final WebSoakKpiProbeService service;
    private final String requiredKey;

    public WebSoakKpiProbeController(WebSoakKpiProbeService service, Environment env) {
        this.service = service;
        this.requiredKey = env.getProperty("probe.websoak-kpi.key", "");
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(
            @RequestBody(required = false) Request req,
            @RequestHeader(value = "X-Internal-Key", required = false) String key
    ) {
        if (requiredKey != null && !requiredKey.isBlank()) {
            if (key == null || !requiredKey.equals(key)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "error", "unauthorized",
                        "message", "Missing/invalid X-Internal-Key"
                ));
            }
        }

        try {
            WebSoakKpiProbeService.Report report = service.run(req);
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "message", bad.getMessage()
            ));
        } catch (Exception e) {
            log.error("[WebSoakKPI] probe failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "internal_error",
                    "message", e.getMessage()
            ));
        }
    }

    @Data
    public static class Request {
        /**
         * Total number of calls (20~50 recommended).
         */
        private Integer iterations;

        /**
         * topK for HybridWebSearchProvider.search(query, topK)
         */
        private Integer topK;

        /**
         * Single query (used when queries is empty and baseline query extraction is off/failed)
         */
        private String query;

        /**
         * Explicit queries (cycled). If provided, baseline query extraction is skipped.
         */
        private java.util.List<String> queries;

        /**
         * Baseline log file path (e.g., ./X_Brave.txt)
         */
        private String baselineFile;

        /**
         * When true (default), tries to extract queries from baselineFile ("Search Trace - query: ...")
         */
        private Boolean useBaselineQueries;

        /**
         * Optional: BRAVE / NAVER. If set, will be applied to GuardContext.webPrimary for each run.
         */
        private String webPrimary;

        /**
         * Optional: sleep between calls (ms) to reduce rate-limit pressure.
         */
        private Long sleepMsBetween;

        /**
         * Optional: when true, sets MDC dbgSearch=1 for richer await event traces.
         */
        private Boolean dbgSearch;
    }
}
