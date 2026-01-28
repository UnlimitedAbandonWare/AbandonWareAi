package com.example.lms.probe;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.probe.dto.SoakProbeRequest;
import com.example.lms.probe.dto.SoakProbeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * SoakProbeController
 *
 * 관리자용: NightmareBreaker OPEN→HALF_OPEN→CLOSED 전이를 짧게 재현/관찰한다.
 * - 실제 운영 로직에는 영향 없음(단, probe가 enabled이고 토큰이 맞아야만 실행).
 */
@RestController
@RequestMapping("/api/probe")
@ConditionalOnProperty(name = "probe.soak.enabled", havingValue = "true", matchIfMissing = false)
public class SoakProbeController {

    private final NightmareBreaker breaker;
    private final NightmareBreakerProperties breakerProps;
    private final boolean enabled;
    private final String adminToken;

    public SoakProbeController(
            NightmareBreaker breaker,
            NightmareBreakerProperties breakerProps,
            @Value("${probe.soak.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken
    ) {
        this.breaker = breaker;
        this.breakerProps = breakerProps;
        this.adminToken = adminToken;
        if (enabled && ConfigValueGuards.isMissing(adminToken)) {
            this.enabled = false;
            org.slf4j.LoggerFactory.getLogger(SoakProbeController.class)
                    .warn("[ProviderGuard] PROBE_TOKEN missing -> probe.soak disabled{}", LogCorrelation.suffix());
        } else {
            this.enabled = enabled;
        }
    }

    @PostMapping("/soak")
    public ResponseEntity<?> soak(@RequestBody SoakProbeRequest req,
                                  @RequestHeader(value = "X-Probe-Token", required = false) String token) {
        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }
        if (breaker == null || breakerProps == null) {
            return ResponseEntity.status(500).body(err("BREAKER_UNAVAILABLE"));
        }

        final SoakProbeResponse resp = new SoakProbeResponse();

        String key = (req != null && req.key != null && !req.key.isBlank()) ? req.key : "soak:test";
        int cycles = (req == null) ? 1 : Math.max(1, req.cycles);

        // Clamp to keep API responsive even if someone passes huge values.
        long openMs = (req == null) ? 200 : req.openDurationMs;
        if (openMs <= 0) openMs = breakerProps.getOpenDuration().toMillis();
        openMs = Math.min(Math.max(openMs, 10L), 5000L);

        int maxCalls = (req == null) ? 1 : req.halfOpenMaxCalls;
        if (maxCalls <= 0) maxCalls = breakerProps.getHalfOpenMaxCalls();
        maxCalls = Math.min(Math.max(maxCalls, 1), 10);

        int successThreshold = (req == null) ? 1 : req.halfOpenSuccessThreshold;
        if (successThreshold <= 0) successThreshold = breakerProps.getHalfOpenSuccessThreshold();
        successThreshold = Math.min(Math.max(successThreshold, 1), 10);

        boolean failOnce = req != null && req.failOnceInHalfOpen;

        synchronized (breakerProps) {
            // Save originals
            Duration oldOpen = breakerProps.getOpenDuration();
            int oldMaxCalls = breakerProps.getHalfOpenMaxCalls();
            int oldSucc = breakerProps.getHalfOpenSuccessThreshold();
            boolean oldHalfOpen = breakerProps.isHalfOpenEnabled();

            try {
                breakerProps.setOpenDuration(Duration.ofMillis(openMs));
                breakerProps.setHalfOpenEnabled(true);
                breakerProps.setHalfOpenMaxCalls(maxCalls);
                breakerProps.setHalfOpenSuccessThreshold(successThreshold);

                resp.steps.add(new SoakProbeResponse.Step("init", breaker.inspect(key),
                        "openDurationMs=" + openMs + ", halfOpenMaxCalls=" + maxCalls + ", halfOpenSuccessThreshold=" + successThreshold));

                for (int c = 1; c <= cycles; c++) {
                    // 1) Trip OPEN
                    int n = Math.max(1, breakerProps.getFailureThreshold());
                    for (int i = 0; i < n; i++) {
                        breaker.recordFailure(key, NightmareBreaker.FailureKind.TIMEOUT, new TimeoutException("soak:trip"), "soak:trip");
                    }
                    resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":afterTripOpen", breaker.inspect(key), "recordFailure x" + n));

                    // 2) Wait until OPEN expires
                    try {
                        Thread.sleep(openMs + 15L);
                    } catch (InterruptedException ie) {
                        Thread.interrupted(); // clear
                        resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":sleepInterrupted", breaker.inspect(key), "sleep interrupted"));
                    }

                    // 3) Trigger HALF_OPEN transition via check
                    try {
                        breaker.checkOpenOrThrow(key);
                    } catch (NightmareBreaker.OpenCircuitException oce) {
                        // If still OPEN, keep the exception note for visibility (should be rare unless clock skew).
                        resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":stillOpen", breaker.inspect(key),
                                "remaining=" + oce.remaining()));
                    }
                    resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":afterCheck", breaker.inspect(key), "checkOpenOrThrow"));

                    // 4) HALF_OPEN trials
                    boolean injectedFail = false;
                    for (int t = 1; t <= maxCalls; t++) {
                        try {
                            breaker.checkOpenOrThrow(key);
                        } catch (NightmareBreaker.OpenCircuitException oce) {
                            resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":trial:" + t + ":blocked", breaker.inspect(key),
                                    "blocked: " + oce.getMessage()));
                            break;
                        }

                        if (failOnce && !injectedFail) {
                            injectedFail = true;
                            // HALF_OPEN에서 실패가 발생하면 즉시 OPEN으로 복귀해야 한다.
                            breaker.recordFailure(key, NightmareBreaker.FailureKind.TIMEOUT, new TimeoutException("soak:half-open-fail"),
                                    "soak:half-open-fail");
                            resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":trial:" + t + ":failInjected", breaker.inspect(key),
                                    "half-open failure injected"));
                            break;
                        }

                        breaker.recordSuccess(key, 5L);
                        resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":trial:" + t + ":success", breaker.inspect(key),
                                "recordSuccess"));

                        // Close early if transitioned
                        NightmareBreaker.StateView v = breaker.inspect(key);
                        if (v != null && v.mode == NightmareBreaker.BreakerMode.CLOSED) {
                            resp.steps.add(new SoakProbeResponse.Step("cycle:" + c + ":closed", v, "HALF_OPEN→CLOSED"));
                            break;
                        }
                    }
                }

                resp.steps.add(new SoakProbeResponse.Step("done", breaker.inspect(key), "final state"));
                return ResponseEntity.ok(resp);
            } finally {
                // Restore originals
                breakerProps.setOpenDuration(oldOpen);
                breakerProps.setHalfOpenMaxCalls(oldMaxCalls);
                breakerProps.setHalfOpenSuccessThreshold(oldSucc);
                breakerProps.setHalfOpenEnabled(oldHalfOpen);
            }
        }
    }

    private static java.util.Map<String, String> err(String code) {
        return java.util.Map.of("error", code, "code", code);
    }
}
