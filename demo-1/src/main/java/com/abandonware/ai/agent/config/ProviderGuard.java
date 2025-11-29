package com.abandonware.ai.agent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderGuard {
    @Value("${llm.provider:}")
    String provider;

    \1
        // Local/OpenAI provider sanity checks (injected)
        try {
            String provider = System.getProperty("llm.provider", System.getenv("LLM_PROVIDER"));
            if (provider != null) {
                if ("openai".equalsIgnoreCase(provider)) {
                    String apiKey = System.getenv("OPENAI_API_KEY");
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("Provider is 'openai' but OPENAI_API_KEY is not set.");
                    }
                }
                if (provider.toLowerCase().startsWith("local")) {
                    // TODO: validate llmrouter.models presence
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("ProviderGuard validation failed: " + t.getMessage(), t);
        }
        
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException("llm.provider must be set (property required).");
        }
        // Example ENV + property conflict check
        String env = System.getenv("LLM_PROVIDER");
        if (env != null && !env.isBlank()) {
            throw new IllegalStateException("Both property(llm.provider) and ENV(LLM_PROVIDER) present; aborting.");
        }
    }
}