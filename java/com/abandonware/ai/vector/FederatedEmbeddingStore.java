package com.abandonware.ai.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FederatedEmbeddingStore {

    @Value("${retrieval.vector.enabled:true}")
    private boolean enabled;

    @Value("${vector.routing.default-weight:1.0}")
    private double defaultWeight;

    public boolean isEnabled() { return enabled; }
    public double getDefaultWeight() { return defaultWeight; }

    public float[] query(String text) {
        // placeholder: return zero vector
        return new float[768];
    }
}
