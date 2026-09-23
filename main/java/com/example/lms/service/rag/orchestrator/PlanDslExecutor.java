package com.example.lms.service.rag.orchestrator;

import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal PlanDslExecutor
 * Loads a YAML plan from classpath:/plans/{planId} if available.
 * No hard dependency on SnakeYAML to keep this module integration-safe.
 */
public class PlanDslExecutor {
    private static final Logger log = LoggerFactory.getLogger(PlanDslExecutor.class);

    public Map<String,Object> loadPlan(String planId) {
        String path = "/plans/" + planId;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return new HashMap<>();
            // NOTE: keep payload opaque to avoid YAML lib dependency here
            Map<String,Object> m = new HashMap<>();
            m.put("planId", planId);
            m.put("source", "classpath:" + path);
            return m;
        } catch (Exception e) {
            log.debug("[PlanDslExecutor] fail-soft stage=loadPlan err=plan-load-failure");
            return new HashMap<>();
        }
    }
}
