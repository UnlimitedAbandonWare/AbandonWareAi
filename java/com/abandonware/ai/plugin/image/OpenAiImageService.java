package com.abandonware.ai.plugin.image;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import com.abandonware.ai.infra.resilience.SimpleCircuitBreaker;

@Service
@ConditionalOnProperty(prefix = "openai.image", name = "enabled", havingValue = "true")
public class OpenAiImageService {
private final boolean enabled;
private final long timeoutMs;
private final SimpleCircuitBreaker cb = new SimpleCircuitBreaker(3, 10_000);

public OpenAiImageService(@Value("${external.image.enabled:true}") boolean enabled,
                          @Value("${external.image.timeout-ms:3000}") long timeoutMs) {
    this.enabled = enabled;
    this.timeoutMs = timeoutMs;
}

    public String generate(String prompt) {
        return "{\"url\":\"about:blank\"}";
    }
}