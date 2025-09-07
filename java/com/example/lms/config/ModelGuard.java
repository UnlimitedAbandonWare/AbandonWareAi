package com.example.lms.config;

import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.util.StringUtils;

/**
 * Guard bean to ensure that essential LLM configuration properties are
 * present and valid at application startup.  Without proper values the
 * application may silently fall back to default providers (such as
 * OpenAI), which is forbidden.  The guard validates the presence of
 * provider, model, base URL and API key settings and rejects
 * incompatible combinations (e.g. using an OpenAI provider with a
 * non‑OpenAI base URL).
 */
@EnableConfigurationProperties(ModelGuard.LlmProps.class)
@Component
public class ModelGuard {

    private final LlmProps p;

    public ModelGuard(LlmProps p) {
        this.p = p;
    }

    /**
     * Verify that all required configuration values are present and
     * consistent.  If a property is missing or an invalid combination is
     * detected, an IllegalStateException is thrown to prevent the
     * application from starting with a partial or incorrect LLM
     * configuration.
     */
    @PostConstruct
    public void verify() {
        if (!StringUtils.hasText(p.provider())) {
            throw new IllegalStateException("[LLM] provider missing (llm.provider)");
        }
        if (!StringUtils.hasText(p.chatModel())) {
            throw new IllegalStateException("[LLM] chat-model missing (llm.chat-model) for provider=" + p.provider());
        }
        if (!StringUtils.hasText(p.apiKey())) {
            throw new IllegalStateException("[LLM] api-key missing (llm.api-key) for provider=" + p.provider());
        }
        // When using the OpenAI provider the base URL must be the canonical OpenAI API
        if ("openai".equalsIgnoreCase(p.provider()) && !"https://api.openai.com".equalsIgnoreCase(p.baseUrl())) {
            throw new IllegalStateException("[LLM] OpenAI provider must use https://api.openai.com");
        }
    }

    /**
     * Nested configuration properties class that binds the llm.* values
     * into a structured object.  Spring requires getters and setters to
     * populate the fields from configuration sources.
     */
    @ConfigurationProperties(prefix="llm")
    public static class LlmProps {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        public String provider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String baseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String apiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String chatModel() { return chatModel; }
        public void setChatModel(String v) { this.chatModel = v; }
    }
}