package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentSingleAttemptRetryTest {

    @Test
    void cancellationBetweenProviderAttemptsDoesNotInvokeFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                new MockEnvironment().withProperty("agent.subagent.glm.enabled", "true"),
                () -> "unit-test-provider-credential",
                (key, task, timeoutMs) -> {
                    throw new LlmGatewayException("httpStatus: 503", LlmFailureClass.HEALTH_DOWN);
                });
        SubagentProvider fallback = new SubagentProvider() {
            public String id() { return "openai"; }
            public int order() { return 20; }
            public Availability availability() { return Availability.enabled(); }
            public String execute(SubagentTask task, long timeoutMs) {
                fallbackCalls.incrementAndGet();
                return "fallback answer";
            }
        };
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, fallback), new LlmGatewayFailureClassifier(),
                60_000L, System::nanoTime, 0, 0L);
        SubagentResult cancelled;
        try {
            cancelled = chain.execute(
                    new SubagentTask("cancelled-during-failover", 0, "first", "analysis", "bounded", Map.of()),
                    new SubagentProviderChain.AttemptBudget(), System.nanoTime() + 10_000_000_000L,
                    attempt -> {
                        // Model the worker interrupt delivered by the owning flow at this boundary.
                        if (attempt.outcome() == SubagentResult.AttemptOutcome.FAILED) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(fallbackCalls).hasValue(0);
        assertThat(cancelled.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(cancelled.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
        assertThat(chain.circuitState("openai")).isEqualTo("closed");
    }

    @Test
    void alreadyCancelledDelegateLeavesTheOneShotActivationProbeAvailable() {
        AtomicInteger calls = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                new MockEnvironment().withProperty("agent.subagent.glm.enabled", "true"),
                () -> "unit-test-provider-credential",
                (key, task, timeoutMs) -> {
                    calls.incrementAndGet();
                    return "bounded answer";
                }, activation);
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm), new LlmGatewayFailureClassifier()),
                metrics, null, activation);
        SubagentTask task = new SubagentTask("cancelled-before-probe", 0, "first",
                "analysis", "bounded", Map.of());
        SubagentResult cancelled;
        Thread.currentThread().interrupt();
        try {
            cancelled = core.executeMcpDelegate(task, new SubagentProviderChain.AttemptBudget(),
                    System.nanoTime() + 10_000_000_000L, ignored -> { });
        } finally {
            Thread.interrupted();
        }

        assertThat(cancelled.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(calls).hasValue(0);
        assertThat(activation.snapshot().state()).isEqualTo(GlmActivationStateMachine.State.READY_TO_PROBE);
        assertThat(activation.snapshot().probeClaimed()).isFalse();
        assertThat(activation.snapshot().liveProbeAttempted()).isFalse();

        SubagentResult next = core.executeMcpDelegate(task, new SubagentProviderChain.AttemptBudget(),
                System.nanoTime() + 10_000_000_000L, ignored -> { });
        assertThat(next.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(calls).hasValue(1);
    }

    @Test
    void alreadyCancelledTaskDoesNotCallAProviderOrSpendItsFlowBudget() {
        AtomicInteger calls = new AtomicInteger();
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                new MockEnvironment().withProperty("agent.subagent.glm.enabled", "true"),
                () -> "unit-test-provider-credential",
                (key, task, timeoutMs) -> {
                    calls.incrementAndGet();
                    return "bounded answer";
                });
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm), new LlmGatewayFailureClassifier());
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();
        SubagentTask task = new SubagentTask("cancelled-before-dispatch", 0, "first",
                "analysis", "bounded", Map.of());
        SubagentResult cancelled;
        Thread.currentThread().interrupt();
        try {
            cancelled = chain.execute(task, budget, System.nanoTime() + 10_000_000_000L, ignored -> { });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(cancelled.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(cancelled.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
        assertThat(calls).hasValue(0);
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("closed");

        SubagentResult next = chain.execute(task, budget, System.nanoTime() + 10_000_000_000L, ignored -> { });
        assertThat(next.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureClass.class, names = {"RATE_LIMIT_COOLDOWN", "HEALTH_DOWN"})
    void transientGlmFailureUsesOneRetryWithinOneFlowReservation(LlmFailureClass failure) {
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                new MockEnvironment().withProperty("agent.subagent.glm.enabled", "true"),
                () -> "unit-test-provider-credential",
                (key, task, timeoutMs) -> {
                    glmCalls.incrementAndGet();
                    throw new LlmGatewayException(
                            failure == LlmFailureClass.HEALTH_DOWN ? "httpStatus: 503" : "httpStatus: 429",
                            failure);
                });
        SubagentProvider fallback = new SubagentProvider() {
            public String id() { return "openai"; }
            public int order() { return 20; }
            public Availability availability() { return Availability.enabled(); }
            public String execute(SubagentTask task, long timeoutMs) {
                fallbackCalls.incrementAndGet();
                return "fallback answer";
            }
        };
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm, fallback), new LlmGatewayFailureClassifier(),
                0L, System::nanoTime, 1, 0L);
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();
        long deadline = System.nanoTime() + 10_000_000_000L;

        SubagentResult first = chain.execute(
                new SubagentTask("single-attempt", 0, "first", "analysis", "bounded", Map.of()),
                budget, deadline, ignored -> { });
        SubagentResult next = chain.execute(
                new SubagentTask("single-attempt", 1, "next", "analysis", "bounded", Map.of()),
                budget, deadline, ignored -> { });

        assertThat(first.selectedProvider()).isEqualTo("openai");
        assertThat(first.output()).isEqualTo("fallback answer");
        assertThat(first.fallbackUsed()).isTrue();
        assertThat(glmCalls).hasValue(2);
        assertThat(fallbackCalls).hasValue(2);
        assertThat(first.attempts())
                .filteredOn(attempt -> attempt.provider().equals("vercel-glm")
                        && attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED)
                .hasSize(2);
        assertThat(first.attempts()).filteredOn(attempt ->
                "retry_scheduled".equals(attempt.reasonCode())).hasSize(1);
        assertThat(next.selectedProvider()).isEqualTo("openai");
        assertThat(next.attempts().get(0).reasonCode()).isEqualTo("flow_attempt_limit");
    }

    @ParameterizedTest
    @EnumSource(value = LlmFailureClass.class, names = {"RATE_LIMIT_COOLDOWN", "HEALTH_DOWN"})
    void transientGlmFailureCanRecoverInsideItsClaimedFlowSlot(LlmFailureClass failure) {
        AtomicInteger calls = new AtomicInteger();
        SubagentProvider glm = SubagentProviderConfiguration.glmProvider(
                new MockEnvironment().withProperty("agent.subagent.glm.enabled", "true"),
                () -> "unit-test-provider-credential",
                (key, task, timeoutMs) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new LlmGatewayException(
                                failure == LlmFailureClass.HEALTH_DOWN ? "httpStatus: 503" : "httpStatus: 429",
                                failure);
                    }
                    return "recovered answer";
                });
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm), new LlmGatewayFailureClassifier(), 0L, System::nanoTime, 1, 0L);
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();
        long deadline = System.nanoTime() + 10_000_000_000L;
        SubagentTask task = new SubagentTask("logical-flow-slot", 0, "first", "analysis", "bounded", Map.of());

        SubagentResult recovered = chain.execute(task, budget, deadline, ignored -> { });
        SubagentResult next = chain.execute(task, budget, deadline, ignored -> { });

        assertThat(recovered.status()).isEqualTo(SubagentResult.Status.SUCCESS);
        assertThat(recovered.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(recovered.output()).isEqualTo("recovered answer");
        assertThat(calls).hasValue(2);
        assertThat(next.status()).isEqualTo(SubagentResult.Status.FAILED);
        assertThat(next.attempts()).singleElement().satisfies(attempt ->
                assertThat(attempt.reasonCode()).isEqualTo("flow_attempt_limit"));
    }
}
