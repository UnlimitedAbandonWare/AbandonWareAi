package com.abandonware.ai.agent.policy;


/**
 * shim policy enforcer invoked around tool calls.  Integrating a policy
 * enforcer allows administrators to deny certain tools based on user
 * attributes or other runtime criteria.  The default implementation
 * performs no checks.
 */
public class ToolPolicyEnforcer {
    public void beforeCall(String toolId) {
        // no-op
    }

    public void afterCall(String toolId) {
        // no-op
    }
}