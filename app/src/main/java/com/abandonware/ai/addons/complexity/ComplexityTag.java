package com.abandonware.ai.addons.complexity;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.complexity.ComplexityTag
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.complexity.ComplexityTag
role: config
*/
public enum ComplexityTag {
    SIMPLE,
    COMPLEX,
    WEB_REQUIRED,
    DOMAIN_SPECIFIC
}