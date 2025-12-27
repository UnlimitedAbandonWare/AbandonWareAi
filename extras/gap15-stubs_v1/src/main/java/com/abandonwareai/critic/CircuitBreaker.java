package com.abandonwareai.critic;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.critic.CircuitBreaker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonwareai.critic.CircuitBreaker
role: config
*/
public class CircuitBreaker {
    private int maxRetries=2; public boolean allowRetry(int attempt){ return attempt<maxRetries; }

}