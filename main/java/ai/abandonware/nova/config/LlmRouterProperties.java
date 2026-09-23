package ai.abandonware.nova.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        /** Route-level toggle. Direct disabled routes fail closed; auto skips them. */
        private boolean enabled = true;

        /** Provider/model identifier understood by the target OpenAI-compatible server. */
        private String name;

        /** Base URL (scheme+host+port, optional /v1). */
        private String baseUrl;

        /** Relative preference weight (also used as a tie-breaker). */
        private double weight = 1.0d;

        /** Optional provider hint used only for diagnostics and gateway scoring. */
        private String provider;

        /** Optional route stage hint such as chat, judge, coder, vision, or embedding. */
        private String stage;

        /** Explicit fallback route key. Used only when llm.gateway cloud fallback is enabled. */
        private String fallbackKey;

        /** Explicit local route used only for endpoint/device quarantine failover. */
        private String deviceFallbackKey;

        /** Explicit hardware role label; never inferred from an endpoint URL. */
        private String deviceRole;

        /** Keep this route out of auto-pick; direct requests can still target it. */
        private boolean fallbackOnly = false;

        /** Enable metadata/health probe participation for this route. */
        private boolean probeEnabled = true;

        /** Provider-specific credential environment variable name for diagnostics and fail-soft gating. */
        private String credentialEnv;

        /** Route-specific minimum score override. */
        private Integer minRouteScore;

        /** Minimum required context window for this route. */
        private Integer minContextTokens;

        /** Expected embedding dimension for embedding-capable routes. */
        private Integer embeddingDim;

        /** True when this route expects managed File Search/RAG support. */
        private boolean managedFileSearch = false;

        /** Route budget hint in milliseconds. */
        private Long budgetMs;

        /** Require the provider response metadata to attest the configured model identity. */
        private boolean responseModelVerificationRequired = false;

        /** Route-scoped, explicit response model identities accepted in addition to {@link #name}. */
        private List<String> responseModelAliases = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

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

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public boolean isResponseModelVerificationRequired() {
            return responseModelVerificationRequired;
        }

        public void setResponseModelVerificationRequired(boolean responseModelVerificationRequired) {
            this.responseModelVerificationRequired = responseModelVerificationRequired;
        }

        public List<String> getResponseModelAliases() {
            return responseModelAliases;
        }

        public void setResponseModelAliases(List<String> responseModelAliases) {
            this.responseModelAliases = responseModelAliases == null
                    ? new ArrayList<>()
                    : new ArrayList<>(responseModelAliases);
        }

        public String getFallbackKey() {
            return fallbackKey;
        }

        public void setFallbackKey(String fallbackKey) {
            this.fallbackKey = fallbackKey;
        }

        public String getDeviceFallbackKey() {
            return deviceFallbackKey;
        }

        public void setDeviceFallbackKey(String deviceFallbackKey) {
            this.deviceFallbackKey = deviceFallbackKey;
        }

        public String getDeviceRole() {
            return deviceRole;
        }

        public void setDeviceRole(String deviceRole) {
            this.deviceRole = deviceRole;
        }

        public boolean isFallbackOnly() {
            return fallbackOnly;
        }

        public void setFallbackOnly(boolean fallbackOnly) {
            this.fallbackOnly = fallbackOnly;
        }

        public boolean isProbeEnabled() {
            return probeEnabled;
        }

        public void setProbeEnabled(boolean probeEnabled) {
            this.probeEnabled = probeEnabled;
        }

        public String getCredentialEnv() {
            return credentialEnv;
        }

        public void setCredentialEnv(String credentialEnv) {
            this.credentialEnv = credentialEnv;
        }

        public Integer getMinRouteScore() {
            return minRouteScore;
        }

        public void setMinRouteScore(Integer minRouteScore) {
            this.minRouteScore = minRouteScore;
        }

        public Integer getMinContextTokens() {
            return minContextTokens;
        }

        public void setMinContextTokens(Integer minContextTokens) {
            this.minContextTokens = minContextTokens;
        }

        public Integer getEmbeddingDim() {
            return embeddingDim;
        }

        public void setEmbeddingDim(Integer embeddingDim) {
            this.embeddingDim = embeddingDim;
        }

        public boolean isManagedFileSearch() {
            return managedFileSearch;
        }

        public void setManagedFileSearch(boolean managedFileSearch) {
            this.managedFileSearch = managedFileSearch;
        }

        public Long getBudgetMs() {
            return budgetMs;
        }

        public void setBudgetMs(Long budgetMs) {
            this.budgetMs = budgetMs;
        }
    }
}
