package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds llmrouter.* from application-llm.yaml (or imported application.yml).
 *
 * <p>Primary use-case in this codebase:
 * <ul>
 *   <li>Plan YAML can reference logical model ids like {@code llmrouter.light} or {@code llmrouter.gemma}</li>
 *   <li>This class provides the mapping from that logical id -> (baseUrl, modelName)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "llmrouter")
public class LlmRouterProperties {

    /** Master toggle for llmrouter resolution. */
    private boolean enabled = true;

    /**
     * If the requested model is OpenAI-based but OpenAI key is missing,
     * allow fail-soft fallback to {@code llmrouter.auto} (local) instead of crashing.
     */
    private boolean fallbackWhenOpenAiMissing = true;

    /** Cooldown window (ms) after a model records a failure. */
    private long cooldownMs = 20_000L;

    /** Key -> ModelConfig. Keys are referenced via logical ids: {@code llmrouter.<key>}. */
    private Map<String, ModelConfig> models = new HashMap<>();

    /**
     * Optional alias mapping for legacy model ids to deployment-safe ids.
     *
     * <p>Example:
     * <pre>
     * llmrouter:
     *   aliases:
     *     qwen2.5-7b-instruct: ${llm.chat-model}
     * </pre>
     *
     * When a caller requests the left-hand-side id, the router can transparently rewrite it
     * to the right-hand-side value before resolution.
     */
    private Map<String, String> aliases = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFallbackWhenOpenAiMissing() {
        return fallbackWhenOpenAiMissing;
    }

    public void setFallbackWhenOpenAiMissing(boolean fallbackWhenOpenAiMissing) {
        this.fallbackWhenOpenAiMissing = fallbackWhenOpenAiMissing;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = (models == null) ? new HashMap<>() : models;
    }

    public Map<String, String> getAliases() {
        return aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = (aliases == null) ? new HashMap<>() : aliases;
    }

    public static class ModelConfig {
        /** Provider/model identifier understood by the target OpenAI-compatible server. */
        private String name;

        /** Base URL (scheme+host+port, optional /v1). */
        private String baseUrl;

        /** Relative preference weight (also used as a tie-breaker). */
        private double weight = 1.0d;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }
}
