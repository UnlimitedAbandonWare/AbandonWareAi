package com.example.lms.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only orchestration diagnostics.
 *
 * Enabled only when {@code probe.orch.enabled=true}.
 * Protected by {@code X-Probe-Token} matching {@code probe.admin-token}.
 */
@RestController
@RequestMapping("/api/probe")
@ConditionalOnProperty(name = "probe.orch.enabled", havingValue = "true", matchIfMissing = false)
public class OrchProbeController {

    private final NightmareBreaker nightmareBreaker;
    private final boolean enabled;
    private final String adminToken;

    public OrchProbeController(
            NightmareBreaker nightmareBreaker,
            @Value("${probe.orch.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken) {
        this.nightmareBreaker = nightmareBreaker;
        this.adminToken = adminToken;
        if (enabled && ConfigValueGuards.isMissing(adminToken)) {
            this.enabled = false;
            org.slf4j.LoggerFactory.getLogger(OrchProbeController.class)
                    .warn("[ProviderGuard] PROBE_TOKEN missing -> probe.orch disabled{}", LogCorrelation.suffix());
        } else {
            this.enabled = enabled;
        }
    }

    @GetMapping("/orch")
    public ResponseEntity<?> orch(
            @RequestParam(name = "q", required = false) String q,
            @RequestHeader(value = "X-Probe-Token", required = false) String token) {

        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }

        GuardContext ctx = GuardContextHolder.getOrDefault();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", Instant.now().toString());
        out.put("query", q);

        try {
            out.put("breakers.open", nightmareBreaker != null ? nightmareBreaker.snapshot() : Map.of());
        } catch (Throwable t) {
            out.put("breakers.open", Map.of("error", String.valueOf(t)));
        }

        try {
            Map<String, Object> guard = new LinkedHashMap<>();
            if (ctx != null) {
                guard.put("auxDown", ctx.isAuxDown());
                guard.put("auxDegraded", ctx.isAuxDegraded());
                guard.put("auxHardDown", ctx.isAuxHardDown());
                guard.put("strikeMode", ctx.isStrikeMode());
                guard.put("compressionMode", ctx.isCompressionMode());
                guard.put("bypassMode", ctx.isBypassMode());
                guard.put("bypassReason", ctx.getBypassReason());
                guard.put("planId", ctx.getPlanId());
                guard.put("mode", ctx.getMode());
                guard.put("memoryProfile", ctx.getMemoryProfile());
            }
            out.put("guard", ctx == null ? null : guard);
        } catch (Throwable t) {
            out.put("guard", Map.of("error", String.valueOf(t)));
        }

        try {
            out.put("signals", OrchestrationSignals.compute(q, nightmareBreaker, ctx));
        } catch (Throwable t) {
            out.put("signals", Map.of("error", String.valueOf(t)));
        }

        try {
            out.put("trace", TraceStore.getAll());
        } catch (Throwable t) {
            out.put("trace", Map.of("error", String.valueOf(t)));
        }

        return ResponseEntity.ok(out);
    }

    @GetMapping(value = "/orch/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String ui() {
        // The UI itself is a static helper; data fetching still requires X-Probe-Token.
        return """
                <!doctype html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"utf-8\" />
                  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />
                  <title>Orch Probe</title>
                  <style>
                    body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; margin: 16px; }
                    input { width: 520px; max-width: 95vw; padding: 6px; }
                    button { padding: 6px 10px; }
                    pre { background: #111; color: #eee; padding: 12px; overflow: auto; }
                    .row { margin: 8px 0; }
                  </style>
                </head>
                <body>
                  <h2>Orchestration Probe</h2>
                  <div class=\"row\">
                    <label>Probe Token (X-Probe-Token):</label><br/>
                    <input id=\"tok\" placeholder=\"paste token\" />
                  </div>
                  <div class=\"row\">
                    <label>Query (optional):</label><br/>
                    <input id=\"q\" placeholder=\"q=...\" />
                  </div>
                  <div class=\"row\">
                    <button onclick=\"refreshOnce()\">Refresh</button>
                    <button onclick=\"toggleAuto()\" id=\"autoBtn\">Auto: OFF</button>
                  </div>
                  <pre id=\"out\">(no data)</pre>

                  <script>
                    let timer = null;
                    async function refreshOnce() {
                      const tok = document.getElementById('tok').value;
                      const q = document.getElementById('q').value;
                      const url = '/api/probe/orch' + (q ? ('?q=' + encodeURIComponent(q)) : '');
                      const res = await fetch(url, { headers: { 'X-Probe-Token': tok } });
                      const txt = await res.text();
                      document.getElementById('out').textContent = txt;
                    }
                    function toggleAuto() {
                      const btn = document.getElementById('autoBtn');
                      if (timer) {
                        clearInterval(timer);
                        timer = null;
                        btn.textContent = 'Auto: OFF';
                        return;
                      }
                      timer = setInterval(refreshOnce, 2000);
                      btn.textContent = 'Auto: ON';
                      refreshOnce();
                    }
                  </script>
                </body>
                </html>
                """;
    }

    private static Map<String, String> err(String code) {
        return Map.of("error", code, "code", code);
    }
}
