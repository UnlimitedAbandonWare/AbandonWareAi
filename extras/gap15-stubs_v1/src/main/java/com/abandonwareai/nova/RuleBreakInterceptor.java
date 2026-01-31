package com.abandonwareai.nova;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.nova.RuleBreakInterceptor
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.nova.RuleBreakInterceptor
role: config
*/
public class RuleBreakInterceptor {
    public boolean hasToken(String header){ return header!=null && !header.isEmpty(); }

}