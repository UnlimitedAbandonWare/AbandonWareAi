package com.example.lms.api;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.trace.LogCorrelation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only debug endpoint to inspect the *effective* ablation penalty values
 * after YAML/profile merge.
 *
 * <p>Security: enabled only when {@code probe.orch.enabled=true} AND
 * {@code probe.admin-token} is configured. Requests must provide {@code X-Probe-Token}.
 */
@RestController
@RequestMapping("/api/debug")
@ConditionalOnProperty(name = "probe.orch.enabled", havingValue = "true", matchIfMissing = false)
public class AblationDebugController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AblationDebugController.class);

    private final Environment env;
    private final boolean enabled;
    private final String adminToken;

    public AblationDebugController(
            Environment env,
            @Value("${probe.orch.enabled:false}") boolean enabled,
            @Value("${probe.admin-token:}") String adminToken
    ) {
        this.env = env;
        this.adminToken = adminToken;
        if (enabled && ConfigValueGuards.isMissing(adminToken)) {
            this.enabled = false;
            log.warn("[ProviderGuard] PROBE_TOKEN missing -> /api/debug/ablation disabled{}", LogCorrelation.suffix());
        } else {
            this.enabled = enabled;
        }
    }

    @GetMapping("/ablation")
    public ResponseEntity<?> ablation(@RequestHeader(value = "X-Probe-Token", required = false) String token) {
        if (!enabled) {
            return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
        }
        if (adminToken == null || adminToken.isBlank() || !adminToken.equals(token)) {
            return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeProfiles", env.getActiveProfiles());

        Map<String, Object> penalties = new LinkedHashMap<>();
        penalties.put("default", d("uaw.ablation.penalty.default", 0.12));
        penalties.put("websearch.base", d("uaw.ablation.penalty.websearch.base",
                env.getProperty("uaw.ablation.penalty.websearch", Double.class, 0.35)));
        penalties.put("websearch.starvation", d("uaw.ablation.penalty.websearch.starvation", 0.28));
        penalties.put("websearch.domain-misroute", d("uaw.ablation.penalty.websearch.domain-misroute", 0.22));
        penalties.put("query-transformer", d("uaw.ablation.penalty.query-transformer", 0.18));
        penalties.put("retrieval", d("uaw.ablation.penalty.retrieval", 0.20));
        penalties.put("rerank", d("uaw.ablation.penalty.rerank", 0.15));
        out.put("penalty", penalties);

        Map<String, Object> stagePolicy = new LinkedHashMap<>();
        stagePolicy.put("defaultOptionalIrregularityDelta",
                d("orchestration.stage-policy.defaultOptionalIrregularityDelta", 0.05));
        stagePolicy.put("defaultCriticalIrregularityDelta",
                d("orchestration.stage-policy.defaultCriticalIrregularityDelta", 0.10));
        out.put("stagePolicy", stagePolicy);

        return ResponseEntity.ok(out);
    }

    private Double d(String key, double fallback) {
        return env.getProperty(key, Double.class, fallback);
    }

    private static Map<String, String> err(String code) {
        return Map.of("error", code, "code", code);
    }
}
