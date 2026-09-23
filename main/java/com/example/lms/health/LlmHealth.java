package com.example.lms.health;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Lightweight boot-time health check for LLM config.
 * - Does NOT hard-fail when API key is missing unless llm.health.fail-on-missing=true
 * - Skips remote checks for local engines (llamacpp/jlama)
 */
@Component
public class LlmHealth implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmHealth.class);

    @Value("${llm.health.enabled:true}")
    private boolean enabled;

    @Value("${llm.health.fail-on-missing:false}")
    private boolean failOnMissing;

    @Value("${llm.engine:}")
    private String engine;

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.base-url:}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.chat-model:}")
    private String model;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[LLM Health] disabled");
            return;
        }
        String e = engine == null ? "" : engine.trim().toLowerCase();
        if ("llamacpp".equals(e) || "jlama".equals(e)) {
            log.info("[LLM Health] local engineHash={} engineLength={} -> skipping remote key check",
                    SafeRedactor.hashValue(engine), lengthOf(engine));
            return;
        }
        if (isMissingApiKey(provider, apiKey)) {
            String msg = "[LLM Health] llm.api-key is empty; set LLM_API_KEY or disable with llm.health.enabled=false";
            if (failOnMissing) {
                throw new IllegalStateException(msg);
            }
            log.warn(msg);
            return;
        }
        // Optional: we do not call remote at boot to avoid delaying startup.
        log.info("[LLM Health] provider={} baseUrlHost={} baseUrlHash={} modelHash={} modelLength={} (startup ping skipped)",
                provider, safeHost(baseUrl), SafeRedactor.hashValue(baseUrl), SafeRedactor.hashValue(model), lengthOf(model));
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            String host = URI.create(value.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignored) {
            traceSuppressed("llmHealth.safeHost", ignored);
            return "invalid";
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("llm.health.suppressed.stage", safeStage);
        TraceStore.put("llm.health.suppressed.errorType", errorType);
        TraceStore.put("llm.health.suppressed." + safeStage, true);
        TraceStore.put("llm.health.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return failure.getClass().getSimpleName();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean isMissingApiKey(String provider, String apiKey) {
        if ("local".equalsIgnoreCase(provider)) {
            return ConfigValueGuards.isMissingLocalOpenAiCompatKey(apiKey);
        }
        return ConfigValueGuards.isMissing(apiKey);
    }
}
