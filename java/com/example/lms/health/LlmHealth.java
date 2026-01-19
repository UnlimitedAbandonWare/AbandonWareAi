package com.example.lms.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

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
            log.info("[LLM Health] local engine='{}' -> skipping remote key check", engine);
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            String msg = "[LLM Health] llm.api-key is empty; set LLM_API_KEY or disable with llm.health.enabled=false";
            if (failOnMissing) {
                throw new IllegalStateException(msg);
            }
            log.warn(msg);
            return;
        }
        // Optional: we do not call remote at boot to avoid delaying startup.
        log.info("[LLM Health] provider={}, baseUrl={}, model={} (startup ping skipped)", provider, baseUrl, model);
    }
}
