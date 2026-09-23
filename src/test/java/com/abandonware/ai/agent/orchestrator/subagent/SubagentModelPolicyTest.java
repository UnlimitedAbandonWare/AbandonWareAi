package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentModelPolicyTest {
    @Test
    void subscriptionOnlyNeverInvokesAnUnconfirmedMeteredProvider() {
        AtomicInteger meteredCalls = new AtomicInteger();
        SubagentProvider metered = provider("metered", 1, false, meteredCalls, false);
        SubagentProvider local = provider("local", 2, true, new AtomicInteger(), false);
        SubagentResult result = chain(metered, local).execute(task(Map.of(
                "costPolicy", "subscription_only")), null, deadline(), null);

        assertThat(result.provider()).isEqualTo("local");
        assertThat(meteredCalls).hasValue(0);
        assertThat(result.warnings()).contains("cost_policy_excluded");
    }

    @Test
    void taskAdmissionRunsBeforeAnyProviderExecution() {
        AtomicInteger calls = new AtomicInteger();
        SubagentProvider publicOnly = new SubagentProvider() {
            public String id() { return "public-only"; }
            public int order() { return 1; }
            public Availability availability() { return Availability.enabled(); }
            public Availability availability(SubagentTask task) {
                return Boolean.TRUE.equals(task.context().get("publicCodeReview"))
                        ? availability() : Availability.disabled("public_input_required");
            }
            public String execute(SubagentTask task, long timeoutMs) {
                calls.incrementAndGet();
                return "unexpected";
            }
        };
        SubagentResult result = chain(publicOnly).execute(task(Map.of()), null, deadline(), null);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.warnings()).contains("public_input_required");
        assertThat(calls).hasValue(0);
    }

    @Test
    void failedSubscriptionAttemptIsNotRetriedOrFollowedByMeteredFallback() {
        AtomicInteger freeCalls = new AtomicInteger();
        AtomicInteger paidCalls = new AtomicInteger();
        SubagentResult result = chain(
                provider("subscription", 1, true, freeCalls, true),
                provider("paid", 2, false, paidCalls, false))
                .execute(task(Map.of("costPolicy", "subscription_only")), null, deadline(), null);
        assertThat(result.succeeded()).isFalse();
        assertThat(freeCalls).hasValue(1);
        assertThat(paidCalls).hasValue(0);
    }

    @Test
    void misspelledExplicitCostPolicyFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        SubagentResult result = chain(provider("local", 1, true, calls, false))
                .execute(task(Map.of("costPolicy", "subscriptin_only")), null, deadline(), null);
        assertThat(result.succeeded()).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(result.warnings()).contains("invalid_cost_policy");
    }

    @Test
    void absentPolicyPreservesTheExplicitlyConfiguredLegacyChain() {
        AtomicInteger calls = new AtomicInteger();
        SubagentResult result = chain(provider("configured", 1, false, calls, false))
                .execute(task(Map.of()), null, deadline(), null);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.provider()).isEqualTo("configured");
        assertThat(calls).hasValue(1);
    }

    private static SubagentProvider provider(String id, int order, boolean subscription,
                                               AtomicInteger calls, boolean fail) {
        return new SubagentProvider() {
            public String id() { return id; }
            public int order() { return order; }
            public Availability availability() { return Availability.enabled(); }
            public boolean supportsSubscriptionOnly() { return subscription; }
            public boolean retryAllowed() { return !fail; }
            public String execute(SubagentTask task, long timeoutMs) {
                calls.incrementAndGet();
                if (fail) throw new LlmGatewayException("quota_unavailable", LlmFailureClass.RATE_LIMIT_COOLDOWN);
                return "bounded answer";
            }
        };
    }

    private static SubagentProviderChain chain(SubagentProvider... providers) {
        return new SubagentProviderChain(List.of(providers), new LlmGatewayFailureClassifier(),
                0L, System::nanoTime, 1, 0L);
    }

    private static SubagentTask task(Map<String, Object> context) {
        return new SubagentTask("policy-test", 0, "test", "review", "public toy example", context);
    }

    private static long deadline() { return System.nanoTime() + 5_000_000_000L; }
}
