package com.abandonware.ai.agent.policy;

import com.abandonware.ai.agent.contract.ToolManifestEntry;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.trace.TraceContext;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * shim policy enforcer invoked around tool calls.  Integrating a policy
 * enforcer allows administrators to deny certain tools based on user
 * attributes or other runtime criteria.
 */
public class ToolPolicyEnforcer {

    @Value("${agent.tools.policy.enabled:true}")
    private boolean enabled;

    @Value("${agent.tools.policy.disabled-ids:}")
    private String disabledIds;

    @Value("${agent.tools.policy.side-effect.require-admin:true}")
    private boolean sideEffectRequireAdmin;

    public void beforeCall(String toolId) {
        // Backward-compatible hook. Rich checks happen in the overload below.
    }

    public void beforeCall(String toolId,
                           ToolManifestEntry entry,
                           boolean adminAuthorized,
                           boolean scopesSatisfied) {
        if (TraceContext.current().remainingMillis() == 0L) {
            throw new ToolInvocationException(408, "tool_budget_exhausted");
        }
        String id = toolId == null ? "" : toolId.trim();
        if (id.isBlank()) {
            throw ToolInvocationException.badRequest("missing_tool_id");
        }
        if (entry == null) {
            throw ToolInvocationException.notFound("tool_manifest_missing");
        }
        if (!entry.enabled()) {
            throw ToolInvocationException.forbidden("tool_disabled:" + safeReason(entry.disabledReason()));
        }
        if (entry.ownerTokenRequired() && !adminAuthorized) {
            throw ToolInvocationException.forbidden("owner_token_required");
        }
        if (!enabled) {
            return;
        }
        if (disabledIdSet().contains(id.toLowerCase(Locale.ROOT))) {
            throw ToolInvocationException.forbidden("tool_disabled_by_policy");
        }
        if (entry.sideEffectRisk() && sideEffectRequireAdmin && !adminAuthorized && !scopesSatisfied) {
            throw ToolInvocationException.forbidden("side_effect_requires_admin_or_scope");
        }
    }

    public void afterCall(String toolId) {
        // Intentionally value-free; metrics/logging can be added without payloads.
    }

    private Set<String> disabledIdSet() {
        if (disabledIds == null || disabledIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(disabledIds.split(","))
                .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String safeReason(String reason) {
        return SafeRedactor.traceLabelOrFallback(reason, "disabled");
    }
}
