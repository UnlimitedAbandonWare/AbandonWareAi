package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GlmProbeCancellationEvidenceTest {

    enum Stage { AVAILABILITY_EXCEPTION, READINESS_INTERRUPT, BEFORE_INVOCATION, INSIDE_INVOCATION }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void cancellationEvidenceDistinguishesReservationFromInvocation(Stage stage) {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean firstReadiness = new AtomicBoolean(true);
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        SubagentProvider glm = new SubagentProvider() {
            public String id() { return "vercel-glm"; }
            public int order() { return 10; }
            public boolean singleAttemptPerFlow() { return true; }
            public Availability availability() {
                if (firstReadiness.getAndSet(false)) {
                    if (stage == Stage.AVAILABILITY_EXCEPTION) {
                        throw new CancellationException("readiness_cancelled");
                    }
                    if (stage == Stage.READINESS_INTERRUPT) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Availability.enabled();
            }
            public String execute(SubagentTask task, long timeoutMs) {
                calls.incrementAndGet();
                throw new CancellationException("invocation_cancelled");
            }
        };
        GlmAgentCore core = new GlmAgentCore(
                new SubagentProviderChain(List.of(glm), new LlmGatewayFailureClassifier()),
                metrics, null, activation);
        SubagentResult result;
        try {
            result = core.executeMcpDelegate(
                    new SubagentTask("probe-cancellation-evidence", 0, "probe", "analysis", "bounded", Map.of()),
                    new SubagentProviderChain.AttemptBudget(), System.nanoTime() + 10_000_000_000L,
                    attempt -> {
                        if (stage == Stage.BEFORE_INVOCATION
                                && attempt.outcome() == SubagentResult.AttemptOutcome.SELECTED) {
                            Thread.currentThread().interrupt();
                        }
                    });
            if (stage == Stage.READINESS_INTERRUPT || stage == Stage.BEFORE_INVOCATION) {
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }
        } finally {
            Thread.interrupted();
        }

        boolean invoked = stage == Stage.INSIDE_INVOCATION;
        assertThat(result.status()).isEqualTo(SubagentResult.Status.CANCELLED);
        assertThat(result.failureClass()).isEqualTo(LlmFailureClass.CANCELLED_NEUTRAL);
        assertThat(calls).hasValue(invoked ? 1 : 0);
        assertThat(core.status())
                .containsEntry("generationAttempted", invoked)
                .containsEntry("liveProbeAttempted", invoked)
                .containsEntry("activationProbeClaimed", true)
                .containsEntry("activationState", "GLM_DEGRADED")
                .containsEntry("modelAdapterAttemptObserved", false)
                .containsEntry("clientHttpExchangeObserved", false)
                .containsEntry("providerAttemptObserved", false)
                .containsEntry("wireAttemptObserved", false);
        assertThat(metrics.snapshot().glmLiveProbeAttemptCount()).isEqualTo(invoked ? 1L : 0L);
    }
}
