package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * Binds llmrouter.* from application-llm.yaml (or imported application.yml).
 * Example:
 * llmrouter:
 *   models:
 *     gemma:
 *       name: google/gemma-3-27b-it
 *       base-url: http://localhost:11434
 *       weight: 0.55
 */
@ConfigurationProperties(prefix = "llmrouter")
public class LlmRouterProperties {

    private Map<String, ModelConfig> models = new HashMap<>();

    public Map<String, ModelConfig> getModels() {
        return models;
    }
    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    public static class ModelConfig {
        private String name;
        private String baseUrl;
        private double weight;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }
    }
}
