package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentReadinessCancellationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readinessCancellationStopsFallbackWithoutSpendingTheFlowAttempt(boolean wrapped) {
        AtomicBoolean cancelFirst = new AtomicBoolean(true);
        AtomicInteger glmCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        SubagentProvider glm = singleAttemptProvider(() -> {
            if (cancelFirst.getAndSet(false)) {
                CancellationException cancelled = new CancellationException("readiness_cancelled");
                throw wrapped ? new CompletionException(cancelled) : cancelled;
            }
            return SubagentProvider.Availability.enabled();
        }, glmCalls);
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
                List.of(glm, fallback), new LlmGatewayFailureClassifier());
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();

        SubagentResult cancelled = chain.execute(task(0), budget,
                System.nanoTime() + 10_000_000_000L, ignored -> { });

        assertThat(fallbackCalls).hasValue(0);
        assertThat(glmCalls).hasValue(0);
        assertThat(cancelled.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(cancelled.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
        assertThat(cancelled.attempts()).extracting(SubagentResult.Attempt::outcome)
                .containsExactly(SubagentResult.AttemptOutcome.CANCELLED);
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("closed");
        assertThat(chain.circuitState("openai")).isEqualTo("closed");

        SubagentResult next = chain.execute(task(1), budget,
                System.nanoTime() + 10_000_000_000L, ignored -> { });
        assertThat(next.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(glmCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void interruptionDuringReadinessLeavesTheUnusedFlowAttemptAvailable() {
        AtomicBoolean interruptFirst = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        SubagentProvider glm = singleAttemptProvider(() -> {
            if (interruptFirst.getAndSet(false)) {
                // Deterministic checkpoint for an owning flow's worker interruption.
                Thread.currentThread().interrupt();
            }
            return SubagentProvider.Availability.enabled();
        }, calls);
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm), new LlmGatewayFailureClassifier());
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();
        SubagentResult cancelled;
        try {
            cancelled = chain.execute(task(0), budget,
                    System.nanoTime() + 10_000_000_000L, ignored -> { });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(cancelled.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(calls).hasValue(0);
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("closed");

        SubagentResult next = chain.execute(task(1), budget,
                System.nanoTime() + 10_000_000_000L, ignored -> { });
        assertThat(next.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(calls).hasValue(1);
    }

    @Test
    void taskDeadlineExpiringDuringReadinessLeavesTheUnusedFlowAttemptAvailable() {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean expireFirst = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        SubagentProvider glm = singleAttemptProvider(() -> {
            if (expireFirst.getAndSet(false)) {
                clock.set(20_000_000_000L);
            }
            return SubagentProvider.Availability.enabled();
        }, calls);
        SubagentProviderChain chain = new SubagentProviderChain(
                List.of(glm), new LlmGatewayFailureClassifier(), 0L, clock::get, 1, 0L);
        SubagentProviderChain.AttemptBudget budget = new SubagentProviderChain.AttemptBudget();

        SubagentResult expired = chain.execute(task(0), budget, 10_000_000_000L, ignored -> { });

        assertThat(expired.status()).isEqualTo(SubagentResult.Status.TIMEOUT);
        assertThat(expired.failureClass()).isEqualTo(LlmFailureClass.TIMEOUT_SOFT);
        assertThat(calls).hasValue(0);
        assertThat(chain.circuitState("vercel-glm")).isEqualTo("closed");

        // A sibling task can still have time left within the enclosing flow deadline.
        SubagentResult next = chain.execute(task(1), budget, 30_000_000_000L, ignored -> { });
        assertThat(next.selectedProvider()).isEqualTo("vercel-glm");
        assertThat(calls).hasValue(1);
    }

    private static SubagentTask task(int index) {
        return new SubagentTask("readiness-boundary", index, "task-" + index,
                "analysis", "bounded", Map.of());
    }

    private static SubagentProvider singleAttemptProvider(
            Supplier<SubagentProvider.Availability> readiness, AtomicInteger calls) {
        return new SubagentProvider() {
            public String id() { return "vercel-glm"; }
            public int order() { return 10; }
            public boolean singleAttemptPerFlow() { return true; }
            public Availability availability() { return readiness.get(); }
            public String execute(SubagentTask task, long timeoutMs) {
                calls.incrementAndGet();
                return "bounded answer";
            }
        };
    }
}
