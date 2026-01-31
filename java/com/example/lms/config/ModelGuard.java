package com.example.lms.config;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.guard.KeyResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guard bean to ensure that essential LLM configuration properties are present and valid at application startup.
 *
 * <p>Purpose: prevent noisy runtime 4XX such as "model is required" by failing fast.</p>
 */
@EnableConfigurationProperties(ModelGuard.LlmProps.class)
@Component
public class ModelGuard {

    private static final Logger log = LoggerFactory.getLogger(ModelGuard.class);

    private final LlmProps p;
    private final Environment env;
    private final KeyResolver keyResolver;
    private final DebugEventStore debugEventStore;

    public ModelGuard(LlmProps p, Environment env, KeyResolver keyResolver, DebugEventStore debugEventStore) {
        this.p = p;
        this.env = env;
        this.keyResolver = keyResolver;
        this.debugEventStore = debugEventStore;
    }

    @PostConstruct
    public void verify() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("engine", p.engine());
        base.put("provider", p.provider());
        base.put("baseUrl", p.baseUrl());
        base.put("chatModel", p.chatModel());
        base.put("apiKey.present", StringUtils.hasText(p.apiKey()));
        base.put("env.OPENAI_API_KEY.present", StringUtils.hasText(env.getProperty("OPENAI_API_KEY")));
        base.put("env.openai.api.key.present", StringUtils.hasText(env.getProperty("openai.api.key")));
        base.put("env.GEMINI_API_KEY.present", StringUtils.hasText(env.getProperty("GEMINI_API_KEY")));
        base.put("env.gemini.api.key.present", StringUtils.hasText(env.getProperty("gemini.api.key")));

        // 0) always require chat-model to prevent "model is required"
        if (!StringUtils.hasText(p.chatModel())) {
            IllegalStateException ex = new IllegalStateException("[LLM] chat-model missing (llm.chat-model)");
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                    "startup.llm.chatModel.missing",
                    ex.getMessage(),
                    "ModelGuard.verify",
                    base,
                    ex);
            throw ex;
        }

        // 1) rgb.moe needs fast endpoint separation (GREEN)
        boolean rgbEnabled = Boolean.parseBoolean(env.getProperty("rgb.moe.enabled", "false"));
        if (rgbEnabled) {
            String fastBase = env.getProperty("llm.fast.base-url");
            if (!StringUtils.hasText(fastBase)) {
                IllegalStateException ex = new IllegalStateException("[LLM] llm.fast.base-url missing (required when rgb.moe.enabled=true)");
                Map<String, Object> d = new LinkedHashMap<>(base);
                d.put("rgb.moe.enabled", true);
                debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                        "startup.rgb.fastBase.missing",
                        ex.getMessage(),
                        "ModelGuard.verify",
                        d,
                        ex);
                throw ex;
            }
        }

        // 2) keep existing remote sanity checks, but don't over-constrain local provider
        String engine = (p.engine() == null) ? "" : p.engine().trim().toLowerCase();
        if ("llamacpp".equals(engine) || "jlama".equals(engine)) {
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.INFO,
                    "startup.llm.guard.ok.local",
                    "[LLM] ModelGuard verified (local engine)",
                    "ModelGuard.verify",
                    base,
                    null);
            return; // local backend: no remote base-url required
        }

        if (!StringUtils.hasText(p.provider())) {
            IllegalStateException ex = new IllegalStateException("[LLM] provider missing (llm.provider)");
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                    "startup.llm.provider.missing",
                    ex.getMessage(),
                    "ModelGuard.verify",
                    base,
                    ex);
            throw ex;
        }

        // API key is required for non-local providers.
        if (!"local".equalsIgnoreCase(p.provider()) && !StringUtils.hasText(p.apiKey())) {
            IllegalStateException ex = new IllegalStateException("[LLM] api-key missing (llm.api-key) for provider=" + p.provider());
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                    "startup.llm.apiKey.missing",
                    ex.getMessage(),
                    "ModelGuard.verify",
                    base,
                    ex);
            throw ex;
        }

        if ("openai".equalsIgnoreCase(p.provider())
                && StringUtils.hasText(p.baseUrl())
                && !"https://api.openai.com".equalsIgnoreCase(p.baseUrl())) {
            IllegalStateException ex = new IllegalStateException("[LLM] OpenAI provider must use https://api.openai.com");
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                    "startup.llm.openai.baseUrl.invalid",
                    ex.getMessage(),
                    "ModelGuard.verify",
                    base,
                    ex);
            throw ex;
        }

        // Extra: flag potential implicit OpenAI fallback risks (informational)
        boolean openaiKeyPresent = StringUtils.hasText(env.getProperty("OPENAI_API_KEY"))
                || StringUtils.hasText(env.getProperty("openai.api.key"));
        boolean embeddingFallbackEnabled = Boolean.parseBoolean(env.getProperty("embedding.fallback.enabled", "false"));
        if (openaiKeyPresent && !"openai".equalsIgnoreCase(p.provider()) && !embeddingFallbackEnabled) {
            Map<String, Object> d = new LinkedHashMap<>(base);
            d.put("embedding.fallback.enabled", embeddingFallbackEnabled);
            debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.WARN,
                    "startup.openai.key.present.nonOpenai",
                    "[LLM] OPENAI_API_KEY present but llm.provider!=openai (check for implicit fallback / legacy configs)",
                    "ModelGuard.verify",
                    d,
                    null);
        }

        // 3) BLUE optional: warn if enabled but key missing
        boolean blueEnabled = Boolean.parseBoolean(env.getProperty("rgb.moe.blueEnabled", "true"));
        if (rgbEnabled && blueEnabled) {
            try {
                String k = keyResolver.resolveGeminiApiKeyStrict();
                if (!StringUtils.hasText(k)) {
                    log.warn("[RGB] blueEnabled=true but Gemini key missing; BLUE will be disabled (runner will skip).");
                    Map<String, Object> d = new LinkedHashMap<>(base);
                    d.put("rgb.moe.enabled", true);
                    d.put("rgb.moe.blueEnabled", true);
                    debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.WARN,
                            "startup.rgb.blue.keyMissing",
                            "[RGB] blueEnabled=true but Gemini key missing; BLUE will be disabled (runner will skip)",
                            "ModelGuard.verify",
                            d,
                            null);
                }
            } catch (Exception e) {
                // strict conflict should fail fast
                debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.ERROR,
                        "startup.rgb.blue.keyConflict",
                        "[RGB] Gemini key conflict (strict)",
                        "ModelGuard.verify",
                        base,
                        e);
                throw e;
            }
        }

        debugEventStore.emit(DebugProbeType.MODEL_GUARD, DebugEventLevel.INFO,
                "startup.llm.guard.ok",
                "[LLM] ModelGuard verified",
                "ModelGuard.verify",
                base,
                null);
    }

    @ConfigurationProperties(prefix = "llm")
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
