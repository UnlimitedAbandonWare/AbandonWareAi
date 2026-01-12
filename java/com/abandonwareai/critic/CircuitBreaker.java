package com.abandonwareai.critic;

import org.springframework.stereotype.Component;

@Component
public class CircuitBreaker {
    private int maxRetries=2; public boolean allowRetry(int attempt){ return attempt<maxRetries; }

}