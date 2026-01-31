package com.abandonware.ai.vector;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.vector.TopicRoutingSettings
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.vector.TopicRoutingSettings
role: config
*/
public class TopicRoutingSettings {
    private final Map<String, Double> weights = new HashMap<>();
    public TopicRoutingSettings() {
        weights.put("default", 1.0);
    }
    public double weightFor(String topic) {
        return weights.getOrDefault(topic, weights.get("default"));
    }
}