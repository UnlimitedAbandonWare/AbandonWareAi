package com.abandonware.ai.agent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider configuration guard.
 *
 * Goals:
 * - Ensure an LLM provider is configured (via property or env).
 * - Catch ambiguous configuration (property + env both set).
 * - Validate provider-specific required env vars early.
 */
@Component
public class ProviderGuard {

    @Value("${llm.provider:}")
    private String provider;

    @PostConstruct
    public void validate() {
        // Determine effective provider (property wins; env is fallback)
        String envProvider = System.getenv("LLM_PROVIDER");
        String propProvider = provider;

        String effective = (propProvider != null && !propProvider.isBlank())
                ? propProvider
                : (envProvider != null && !envProvider.isBlank() ? envProvider : "");

        if (effective.isBlank()) {
            throw new IllegalStateException(
                    "LLM provider is not configured. Set 'llm.provider' (recommended) or set env LLM_PROVIDER."
            );
        }

        // Disallow ambiguous configuration.
        if (propProvider != null && !propProvider.isBlank() && envProvider != null && !envProvider.isBlank()) {
            throw new IllegalStateException(
                    "Ambiguous provider configuration: both property 'llm.provider' and env 'LLM_PROVIDER' are set. " +
                            "Please set only one."
            );
        }

        // Provider-specific checks.
        String p = effective.trim().toLowerCase();

        if ("openai".equals(p)) {
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Provider 'openai' requires env OPENAI_API_KEY to be set.");
            }
        }

        if (p.startsWith("local")) {
            // Local providers are optional; allow running without keys.
            // Add checks here if you later require a local endpoint variable, e.g. LOCAL_LLM_URL.
        }
    }
}
