package com.abandonware.ai.agent.integrations.web;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.web.RuleBreakInterceptor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.web.RuleBreakInterceptor
role: config
*/
public class RuleBreakInterceptor {
    public boolean isRuleBreak(String header){
        return header != null && !header.isBlank();
    }
}