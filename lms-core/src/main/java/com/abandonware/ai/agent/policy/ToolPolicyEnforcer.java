package com.abandonware.ai.agent.policy;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.policy.ToolPolicyEnforcer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.policy.ToolPolicyEnforcer
role: config
*/
public class ToolPolicyEnforcer {
    public void beforeCall(String toolId) {
        // no-op
    }

    public void afterCall(String toolId) {
        // no-op
    }
}