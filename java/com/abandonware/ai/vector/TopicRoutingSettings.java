package com.abandonware.ai.vector;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TopicRoutingSettings {
    private final Map<String, Double> weights = new HashMap<>();
    public TopicRoutingSettings() {
        weights.put("default", 1.0);
    }
    public double weightFor(String topic) {
        return weights.getOrDefault(topic, weights.get("default"));
    }
}