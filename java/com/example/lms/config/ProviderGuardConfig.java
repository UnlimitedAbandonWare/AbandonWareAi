package com.example.lms.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * (옵션) local-only 환경을 강제하는 가드.
 * llm.provider-guard.require-local=true 일 때만 동작
 */
@Configuration
public class ProviderGuardConfig {

    @Value("${llm.provider-guard.require-local:false}")
    private boolean requireLocal;

    @Value("${llm.provider:}")
    private String provider;

    @Value("${llm.base-url:}")
    private String baseUrl;

    @PostConstruct
    public void validate() {
        if (!requireLocal) return;

        String p = provider == null ? "" : provider.trim();
        String url = baseUrl == null ? "" : baseUrl.trim();

        if (!"local".equalsIgnoreCase(p)) {
            throw new IllegalStateException("ProviderGuard: llm.provider must be 'local' (got: " + p + ")");
        }
        String lower = url.toLowerCase();
        boolean ok = lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("::1");
        if (!ok) {
            throw new IllegalStateException("ProviderGuard: llm.base-url must point to localhost (got: " + url + ")");
        }
    }
}
