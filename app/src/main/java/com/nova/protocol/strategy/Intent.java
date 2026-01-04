package com.nova.protocol.strategy;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.strategy.Intent
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.strategy.Intent
role: config
*/
public enum Intent {
    GENERAL, RECENCY_CRITICAL, FACT_CHECK
}