package com.example.lms.config;

import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.util.StringUtils;

/**
 * Guard bean to ensure that essential LLM configuration properties are present and valid at application startup.
 * - Skips strict checks when a local engine is selected (llamacpp/jlama).
 * - Keeps strict checks for remote providers (openai/groq/...).
 */
@EnableConfigurationProperties(ModelGuard.LlmProps.class)
@Component
public class ModelGuard {

    private final LlmProps p;

    public ModelGuard(LlmProps p) {
        this.p = p;
    }

    @PostConstruct
    public void verify() {
        String engine = (p.engine() == null) ? "" : p.engine().trim().toLowerCase();
        if ("llamacpp".equals(engine) || "jlama".equals(engine)) {
            // local backend: no remote key/base-url required
            return;
        }
        // remote backend sanity checks
        if (!StringUtils.hasText(p.provider())) {
            throw new IllegalStateException("[LLM] provider missing (llm.provider)");
        }
        if (!StringUtils.hasText(p.chatModel())) {
            throw new IllegalStateException("[LLM] chat-model missing (llm.chat-model) for provider=" + p.provider());
        }
        if (!StringUtils.hasText(p.apiKey())) {
            throw new IllegalStateException("[LLM] api-key missing (llm.api-key) for provider=" + p.provider());
        }
        if ("openai".equalsIgnoreCase(p.provider()) && !"https://api.openai.com".equalsIgnoreCase(p.baseUrl())) {
            throw new IllegalStateException("[LLM] OpenAI provider must use https://api.openai.com");
        }
    }

    @ConfigurationProperties(prefix="llm")
    public static class LlmProps {
        private String engine;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String chatModel;
        public String engine() { return engine; }
        public void setEngine(String v) { this.engine = v; }
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
