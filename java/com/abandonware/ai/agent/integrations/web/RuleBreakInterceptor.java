package com.abandonware.ai.agent.integrations.web;


/**
 * RuleBreakInterceptor stub: read header X-RuleBreak-Token and set a flag (no-op here).
 */
public class RuleBreakInterceptor {
    public boolean isRuleBreak(String header){
        return header != null && !header.isBlank();
    }
}