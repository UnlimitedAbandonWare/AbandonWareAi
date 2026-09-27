package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmFailureClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GlmActivationStateMachineTest {

    @Test
    void disabledAutoActivationDoesNotReadKeyOrClaimProbe() {
        AtomicInteger keyPresenceReads = new AtomicInteger();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> false,
                () -> true,
                () -> {
                    keyPresenceReads.incrementAndGet();
                    return true;
                },
                metrics);

        GlmActivationStateMachine.Snapshot snapshot = activation.refresh("closed");

        assertThat(snapshot.state()).isEqualTo(GlmActivationStateMachine.State.BLOCKED_EXTERNAL);
        assertThat(snapshot.reasonCode()).isEqualTo("auto_activation_disabled");
        assertThat(snapshot.liveProbeAttempted()).isFalse();
        assertThat(activation.tryBeginProbe("closed")).isEmpty();
        assertThat(keyPresenceReads).hasValue(0);
        assertThat(metrics.snapshot().glmActivationAttemptCount()).isZero();
    }

    @Test
    void requiresExternalReadyAndClaimsOneProbeAcrossConcurrentCallers() throws Exception {
        AtomicBoolean externalReady = new AtomicBoolean();
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true,
                externalReady::get,
                () -> true,
                metrics);

        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.WAITING_FOR_CREDIT_CONFIRMATION);
        externalReady.set(true);
        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.READY_TO_PROBE);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Optional<GlmActivationStateMachine.ProbePermit>>> claims = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                claims.add(() -> activation.tryBeginProbe("closed"));
            }
            List<Future<Optional<GlmActivationStateMachine.ProbePermit>>> futures =
                    executor.invokeAll(claims);

            assertThat(futures).extracting(future -> future.get().isPresent())
                    .containsOnlyOnce(true);
            assertThat(activation.snapshot().state())
                    .isEqualTo(GlmActivationStateMachine.State.PROBING);
            assertThat(metrics.snapshot().glmActivationAttemptCount()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fallbackSuccessNeverPromotesGlmActiveOrRearmsTheSameReadinessEpoch() {
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = readyActivation(metrics);
        GlmActivationStateMachine.ProbePermit permit = activation.tryBeginProbe("closed").orElseThrow();
        SubagentResult fallbackSuccess = new SubagentResult(
                "probe-request",
                0,
                "probe-task",
                "analysis",
                SubagentResult.Status.SUCCESS,
                "fallback answer",
                "ollama",
                LlmFailureClass.NONE,
                List.of(
                        attempt("vercel-glm", SubagentResult.AttemptOutcome.SELECTED,
                                LlmFailureClass.NONE, false, "selected"),
                        attempt("vercel-glm", SubagentResult.AttemptOutcome.FAILED,
                                LlmFailureClass.AUTH_MISSING, false, "responses_http_403"),
                        attempt("ollama", SubagentResult.AttemptOutcome.SELECTED,
                                LlmFailureClass.NONE, true, "selected"),
                        attempt("ollama", SubagentResult.AttemptOutcome.SUCCESS,
                                LlmFailureClass.NONE, true, "success")),
                12L);

        activation.completeProbe(
                permit,
                fallbackSuccess,
                GlmActivationStateMachine.ProbeEvidence.clientOnly(true));

        GlmActivationStateMachine.Snapshot snapshot = activation.snapshot();
        assertThat(snapshot.state()).isEqualTo(GlmActivationStateMachine.State.BLOCKED_EXTERNAL);
        assertThat(snapshot.reasonCode()).isEqualTo("responses_http_403");
        assertThat(snapshot.liveProbeAttempted()).isTrue();
        assertThat(snapshot.liveProbeSucceeded()).isFalse();
        assertThat(snapshot.liveProbeFallbackUsed()).isTrue();
        assertThat(snapshot.liveProbeProvider()).isEqualTo("vercel-glm");
        assertThat(snapshot.aggregateProvider()).isEqualTo("ollama");
        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.BLOCKED_EXTERNAL);
        assertThat(activation.tryBeginProbe("closed")).isEmpty();
        assertThat(metrics.snapshot().glmLiveProbeFailureCount()).isEqualTo(1L);
        assertThat(metrics.snapshot().glmProbeFallbackCount()).isEqualTo(1L);
    }

    @Test
    void clientHttpSuccessWithoutProviderWireProofRemainsDegraded() {
        GlmActivationStateMachine activation = readyActivation(new SubagentAgentMetrics());
        GlmActivationStateMachine.ProbePermit permit = activation.tryBeginProbe("closed").orElseThrow();

        activation.completeProbe(
                permit,
                glmSuccess(),
                GlmActivationStateMachine.ProbeEvidence.clientOnly(true));

        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_DEGRADED);
        assertThat(activation.snapshot().reasonCode()).isEqualTo("wire_evidence_not_observed");
        assertThat(activation.snapshot().liveProbeSucceeded()).isFalse();
        assertThat(activation.snapshot().probeEvidence().providerAttemptObserved()).isFalse();
        assertThat(activation.snapshot().probeEvidence().wireAttemptObserved()).isFalse();
    }

    @Test
    void matchedControlledEvidenceIsRequiredForTheActiveTransition() {
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = readyActivation(metrics);
        GlmActivationStateMachine.ProbePermit permit = activation.tryBeginProbe("closed").orElseThrow();

        activation.completeProbe(
                permit,
                glmSuccess(),
                new GlmActivationStateMachine.ProbeEvidence(
                        true, true, true, true, true, true, true, true));

        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_ACTIVE);
        assertThat(activation.snapshot().liveProbeSucceeded()).isTrue();
        assertThat(metrics.snapshot().glmLiveProbeSuccessCount()).isEqualTo(1L);
    }

    @Test
    void circuitReadinessSupersedesVerifiedActiveStateWithoutRearmingProbe() {
        GlmActivationStateMachine activation = readyActivation(new SubagentAgentMetrics());
        GlmActivationStateMachine.ProbePermit permit = activation.tryBeginProbe("closed").orElseThrow();
        activation.completeProbe(
                permit,
                glmSuccess(),
                new GlmActivationStateMachine.ProbeEvidence(
                        true, true, true, true, true, true, true, true));

        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_ACTIVE);
        assertThat(activation.glmProviderAttemptAllowed()).isTrue();

        GlmActivationStateMachine.Snapshot authBlocked = activation.refresh("auth_blocked");
        assertThat(authBlocked.state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_AUTH_BLOCKED);
        assertThat(authBlocked.reasonCode()).isEqualTo("circuit_auth_blocked");
        assertThat(activation.glmProviderAttemptAllowed()).isFalse();
        assertThat(activation.tryBeginProbe("auth_blocked")).isEmpty();

        GlmActivationStateMachine.Snapshot cooldown = activation.refresh("open");
        assertThat(cooldown.state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_DEGRADED);
        assertThat(cooldown.reasonCode()).isEqualTo("circuit_cooldown");
        assertThat(activation.glmProviderAttemptAllowed()).isFalse();
        assertThat(activation.tryBeginProbe("open")).isEmpty();

        GlmActivationStateMachine.Snapshot recovered = activation.refresh("closed");
        assertThat(recovered.state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_ACTIVE);
        assertThat(activation.glmProviderAttemptAllowed()).isTrue();
        assertThat(activation.tryBeginProbe("closed")).isEmpty();
    }

    @Test
    void claimedProbeCompletionSurvivesAReadinessRefreshWithoutRearming() {
        AtomicBoolean externalReady = new AtomicBoolean(true);
        SubagentAgentMetrics metrics = new SubagentAgentMetrics();
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, externalReady::get, () -> true, metrics);
        GlmActivationStateMachine.ProbePermit permit =
                activation.tryBeginProbe("closed").orElseThrow();

        externalReady.set(false);
        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.WAITING_FOR_CREDIT_CONFIRMATION);
        activation.completeProbe(
                permit,
                null,
                GlmActivationStateMachine.ProbeEvidence.none(false));

        assertThat(activation.snapshot().state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_DEGRADED);
        assertThat(activation.snapshot().liveProbeAttempted()).isFalse();
        assertThat(activation.snapshot().liveProbeFallbackUsed()).isFalse();
        assertThat(activation.snapshot().liveProbeFailureClass()).isEqualTo("DISABLED");
        assertThat(activation.snapshot().probeEvidence().generationAttempted()).isFalse();
        assertThat(metrics.snapshot().glmLiveProbeAttemptCount()).isZero();
        assertThat(metrics.snapshot().glmLiveProbeFailureCount()).isZero();
        assertThat(metrics.snapshot().glmProbeFallbackCount()).isZero();
        externalReady.set(true);
        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.GLM_DEGRADED);
        assertThat(activation.tryBeginProbe("closed")).isEmpty();
    }

    private static GlmActivationStateMachine readyActivation(SubagentAgentMetrics metrics) {
        GlmActivationStateMachine activation = new GlmActivationStateMachine(
                () -> true, () -> true, () -> true, metrics);
        assertThat(activation.refresh("closed").state())
                .isEqualTo(GlmActivationStateMachine.State.READY_TO_PROBE);
        return activation;
    }

    private static SubagentResult glmSuccess() {
        return new SubagentResult(
                "probe-request",
                0,
                "probe-task",
                "analysis",
                SubagentResult.Status.SUCCESS,
                "bounded GLM answer",
                "vercel-glm",
                LlmFailureClass.NONE,
                List.of(
                        attempt("vercel-glm", SubagentResult.AttemptOutcome.SELECTED,
                                LlmFailureClass.NONE, false, "selected"),
                        attempt("vercel-glm", SubagentResult.AttemptOutcome.SUCCESS,
                                LlmFailureClass.NONE, false, "success")),
                10L);
    }

    private static SubagentResult.Attempt attempt(String provider,
                                                   SubagentResult.AttemptOutcome outcome,
                                                   LlmFailureClass failureClass,
                                                   boolean fallback,
                                                   String reasonCode) {
        return new SubagentResult.Attempt(provider, outcome, failureClass, 1L, fallback, reasonCode);
    }
}
