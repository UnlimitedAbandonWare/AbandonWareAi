package com.abandonware.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.abandonware.ai.infra.resilience.SimpleCircuitBreaker;

@Service
public class AdaptiveTranslationService {
private final boolean enabled;
private final long timeoutMs;
private final SimpleCircuitBreaker cb = new SimpleCircuitBreaker(3, 10_000);

public AdaptiveTranslationService(@Value("${external.translate.enabled:true}") boolean enabled,
                                  @Value("${external.translate.timeout-ms:1500}") long timeoutMs) {
    this.enabled = enabled;
    this.timeoutMs = timeoutMs;
}

    public String translate(String text, String targetLang) {
        if (!enabled || !cb.allow()) return text;
        // placeholder: echo
        return text;
    }
}