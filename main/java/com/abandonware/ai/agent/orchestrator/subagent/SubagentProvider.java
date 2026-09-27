package com.abandonware.ai.agent.orchestrator.subagent;

import java.util.Objects;

/** External model boundary used by the ordered subagent provider chain. */
public interface SubagentProvider {

    String id();

    int order();

    default boolean singleAttemptPerFlow() {
        return false;
    }

    Availability availability();

    /** Task-specific admission must complete before an execution attempt is reserved. */
    default Availability availability(SubagentTask task) {
        return availability();
    }

    /** Unknown/external billing is excluded from an explicitly subscription-only request. */
    default boolean supportsSubscriptionOnly() {
        return false;
    }

    default boolean retryAllowed() {
        return true;
    }

    String execute(SubagentTask task, long timeoutMs) throws Exception;

    record Availability(boolean available, String reasonCode) {
        public Availability {
            reasonCode = Objects.requireNonNullElse(reasonCode, available ? "enabled" : "disabled");
        }

        public static Availability enabled() {
            return new Availability(true, "enabled");
        }

        public static Availability disabled(String reasonCode) {
            return new Availability(false, reasonCode);
        }
    }
}
