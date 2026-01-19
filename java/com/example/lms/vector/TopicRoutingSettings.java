package com.example.lms.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;




/**
 * Holds per-topic routing weights for federated vector search.  Each topic
 * maps to a set of store identifiers with associated weights.  When a
 * search is executed for a given topic the weights are normalised and used
 * to distribute the {@code topK} across the stores.  A minimum per-store
 * value may also be configured to ensure diversity.  The configuration
 * keys live under {@code vector.routing.*} in the Spring environment.
 */
@Component
public class TopicRoutingSettings {
    private final Map<String, Map<String, Double>> topics;
    private final int minPerStore;

    public TopicRoutingSettings(
            @Value("#{${vector.routing.topics:{}}}") Map<String, Map<String, Double>> topics,
            @Value("${vector.routing.min-per-store:1}") int minPerStore) {
        this.topics = new LinkedHashMap<>();
        if (topics != null) {
            topics.forEach((k, v) -> {
                Map<String, Double> copy = new LinkedHashMap<>();
                if (v != null) {
                    v.forEach((sk, sv) -> copy.put(sk, sv));
                }
                this.topics.put(k, copy);
            });
        }
        this.minPerStore = minPerStore;
    }

    /**
     * Returns the weight map for the given topic.  When the topic is not
     * configured the default weights are returned.  If no default is
     * configured the result may be empty.
     */
    public Map<String, Double> weightsFor(String topic) {
        if (topic == null || topic.isBlank()) {
            return topics.getOrDefault("default", Collections.emptyMap());
        }
        Map<String, Double> weights = topics.get(topic);
        if (weights == null) {
            weights = topics.get("default");
        }
        return (weights != null) ? weights : Collections.emptyMap();
    }

    /**
     * The minimum number of results to allocate to each store.
     */
    public int minPerStore() {
        return minPerStore;
    }
}