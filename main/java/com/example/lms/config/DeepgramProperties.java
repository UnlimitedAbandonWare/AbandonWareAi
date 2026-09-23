package com.example.lms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side credentials for Deepgram clients, available through constructor injection.
 * DEEPGRAM_API_KEY is primary; DEEPGRAM_API_KEY_SECONDARY is used only when primary is absent.
 * Consumers must check isConfigured() before making an external request.
 */
@ConfigurationProperties(prefix = "deepgram")
public class DeepgramProperties {
    private String apiKey = "";
    private String apiKeySecondary = "";

    public String getApiKey() {
        return apiKey.isEmpty() ? apiKeySecondary : apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = normalizeKey(apiKey);
    }

    public String getApiKeySecondary() {
        return apiKeySecondary;
    }

    public void setApiKeySecondary(String apiKeySecondary) {
        this.apiKeySecondary = normalizeKey(apiKeySecondary);
    }

    private static String normalizeKey(String value) {
        return ConfigValueGuards.isMissing(value)
                || "your_deepgram_api_key_here".equalsIgnoreCase(value.trim())
                ? "" : value.trim();
    }

    public boolean isConfigured() {
        return !getApiKey().isEmpty();
    }

    public String getDisabledReason() {
        return isConfigured() ? "" : "missing_api_key";
    }

    @Override
    public String toString() {
        return "DeepgramProperties[configured=" + isConfigured() + ", apiKey=REDACTED, apiKeySecondary=REDACTED]";
    }
}
