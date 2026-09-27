package com.abandonware.ai.agent.policy;

import com.abandonware.ai.agent.contract.ToolManifestEntry;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolPolicyEnforcerBudgetTest {

    @Test
    void expiredSharedRequestBudgetRejectsBeforeToolExecution() {
        ToolPolicyEnforcer policy = enabledPolicy();
        ToolManifestEntry entry = readOnlyEntry("ops.snapshot");

        try (TraceContext ignored = TraceContext.attach("s1", "budget")
                .startWithBudget(Duration.ofNanos(1))) {
            ToolInvocationException ex = assertThrows(
                    ToolInvocationException.class,
                    () -> policy.beforeCall("ops.snapshot", entry, true, true));

            assertEquals(408, ex.status());
            assertEquals("tool_budget_exhausted", ex.code());
        }
    }

    @Test
    void disablingDynamicPolicyDoesNotDisableManifestOwnerOrEnabledInvariants() {
        ToolPolicyEnforcer policy = enabledPolicy();
        ReflectionTestUtils.setField(policy, "enabled", false);
        ToolManifestEntry ownerRequired = new ToolManifestEntry(
                "counter.evidence.retrieve",
                true,
                "stateful read tool",
                "read_only",
                List.of("internal.read"),
                false,
                true,
                16384,
                false,
                "",
                "",
                Map.of());

        ToolInvocationException ownerError = assertThrows(
                ToolInvocationException.class,
                () -> policy.beforeCall(ownerRequired.id(), ownerRequired, false, true));
        assertEquals("owner_token_required", ownerError.code());

        ToolManifestEntry disabled = new ToolManifestEntry(
                "disabled.tool",
                false,
                "disabled tool",
                "read_only",
                List.of("internal.read"),
                false,
                false,
                16384,
                false,
                "",
                "maintenance",
                Map.of());
        ToolInvocationException disabledError = assertThrows(
                ToolInvocationException.class,
                () -> policy.beforeCall(disabled.id(), disabled, true, true));
        assertEquals("tool_disabled:maintenance", disabledError.code());
    }

    private static ToolPolicyEnforcer enabledPolicy() {
        ToolPolicyEnforcer policy = new ToolPolicyEnforcer();
        ReflectionTestUtils.setField(policy, "enabled", true);
        ReflectionTestUtils.setField(policy, "disabledIds", "");
        ReflectionTestUtils.setField(policy, "sideEffectRequireAdmin", true);
        return policy;
    }

    private static ToolManifestEntry readOnlyEntry(String id) {
        return new ToolManifestEntry(
                id,
                true,
                "read-only test tool",
                "read_only",
                List.of("internal.read"),
                false,
                false,
                65536,
                false,
                "",
                "",
                Map.of());
    }
}
