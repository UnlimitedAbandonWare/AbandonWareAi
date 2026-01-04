package com.nova.protocol.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.plan.PlanLoader
 * Role: config
 * Dependencies: com.fasterxml.jackson.databind.ObjectMapper, com.fasterxml.jackson.dataformat.yaml.YAMLFactory
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.plan.PlanLoader
role: config
*/
public class PlanLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    public PlanLoader() {
        yaml.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public Plan loadFromClasspath(String planId) {
        try (InputStream in = getClass().getResourceAsStream("/plans/" + planId + ".yaml")) {
            if (in == null) return null;
            return yaml.readValue(in, Plan.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load plan: " + planId, e);
        }
    }
}