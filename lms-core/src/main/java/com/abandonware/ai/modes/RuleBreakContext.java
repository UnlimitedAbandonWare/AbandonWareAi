package com.abandonware.ai.modes;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.modes.RuleBreakContext
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.modes.RuleBreakContext
role: config
*/
public class RuleBreakContext {
    public boolean allowAnyDomain = false;
    public double webTopKMultiplier = 2.0;
}