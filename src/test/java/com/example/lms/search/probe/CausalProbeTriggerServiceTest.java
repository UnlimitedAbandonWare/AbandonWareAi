package com.example.lms.search.probe;

import ai.abandonware.nova.orch.failpattern.FailurePatternMatch;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CausalProbeTriggerServiceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void triggersReadyOnlyAfterThreeSamplesAndTwoAxesAgree() {
        DebugEventStore debugStore = new DebugEventStore();
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(debugStore), provider(null), provider(null));
        TraceStore.put("rawQuery", "raw query must not leak");
        TraceStore.put("Author" + "ization", "raw-auth-value-must-not-leak");
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "private goal value must not leak",
                "unit-test",
                blackbox,
                List.of(),
                CausalProbeTriggerService.ProbeConstraints.allowOnly("anchor_compression_topup"));

        assertTrue(decision.evidenceReady());
        assertEquals("after_filter_starvation", decision.dominantFailure());
        assertEquals("web_filter", decision.hotspot());
        assertTrue(decision.confidence() >= 0.65d);
        assertEquals("anchor_compression_topup", decision.patchCandidate());
        assertEquals("source_patch_candidate", decision.action());
        assertEquals("allowed", decision.policyDecision());
        assertEquals(Boolean.TRUE, TraceStore.get("causalProbe.evidenceReady"));
        assertEquals(3L, TraceStore.get("causalProbe.sampleCount"));
        assertEquals("after_filter_starvation", TraceStore.get("causalProbe.dominantFailure"));
        assertNotEquals("private goal value must not leak", TraceStore.get("causalProbe.goalHash"));

        String publicPayload = TraceStore.getByPrefix("causalProbe.").toString()
                + debugStore.list(10).toString();
        assertFalse(publicPayload.contains("raw query must not leak"), publicPayload);
        assertFalse(publicPayload.contains("raw-auth-value-must-not-leak"), publicPayload);
        assertFalse(publicPayload.contains("private goal value must not leak"), publicPayload);
        assertTrue(debugStore.list(10).stream()
                .map(DebugEvent::fingerprint)
                .anyMatch(SafeRedactor.hashValue(
                        "causal_probe:after_filter_starvation:source_patch_candidate")::equals));
    }

    @Test
    void causalEvidenceReadyRemainsProbeOnlyAndNeverPassesVerificationGate() {
        DebugEventStore debugStore = new DebugEventStore();
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(debugStore), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "probe-authority-test",
                blackbox,
                List.of(),
                CausalProbeTriggerService.ProbeConstraints.allowOnly("anchor_compression_topup"));

        assertTrue(decision.evidenceReady());
        assertEquals("probe_only", TraceStore.get("causalProbe.decisionAuthority"));
        assertEquals(Boolean.FALSE, TraceStore.get("causalProbe.verificationGatePassed"));
        assertTrue(TraceStore.getByPrefix("counterEvidence.").isEmpty());
        String expectedFingerprint = SafeRedactor.hashValue(
                "causal_probe:" + decision.dominantFailure() + ":" + decision.action());
        DebugEvent event = debugStore.list(10).stream()
                .filter(item -> expectedFingerprint.equals(item.fingerprint()))
                .findFirst()
                .orElseThrow();
        assertEquals("probe_only", event.data().get("decisionAuthority"));
        assertEquals(Boolean.FALSE, event.data().get("verificationGatePassed"));
        assertEquals(decision.constraintHash(), event.data().get("constraintHash"));
    }

    @Test
    void keepsProviderDisabledAsObserveOnlyInsteadOfPatchCandidate() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", "missing_key");
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "credential repair goal",
                "provider-disabled-test",
                blackbox,
                List.of());

        assertTrue(decision.evidenceReady());
        assertEquals("provider_disabled", decision.dominantFailure());
        assertEquals("observe_provider_disabled", decision.patchCandidate());
        assertEquals("observe_only", decision.action());
        assertEquals("observe_only", TraceStore.get("causalProbe.action"));
    }

    @Test
    void needleOutcomeRewarderProjectsCausalProbeFromProbeSeam() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        NeedleOutcomeRewarder rewarder = new NeedleOutcomeRewarder();
        ReflectionTestUtils.setField(rewarder, "causalProbeTriggerService", service);
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);

        rewarder.recordOutcome(NeedleContribution.of(1, 0, -0.10d), -0.2d);

        assertEquals(Boolean.TRUE, TraceStore.get("causalProbe.evidenceReady"));
        assertEquals("after_filter_starvation", TraceStore.get("causalProbe.dominantFailure"));
        assertEquals("observe_only", TraceStore.get("causalProbe.action"));
        assertEquals("observe_only", TraceStore.get("causalProbe.patchCandidate"));
        assertEquals("constraints_missing", TraceStore.get("causalProbe.policyDecision"));
        assertEquals("needleoutcomerewarder.recordoutcome", TraceStore.get("causalProbe.where"));
    }

    @Test
    void operatorStopOverridesReadyEvidenceWithoutLeakingReason() {
        DebugEventStore debugStore = new DebugEventStore();
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(debugStore), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "operator-stop-test",
                blackbox,
                List.of(),
                CausalProbeTriggerService.ProbeConstraints.stop("private operator stop reason"));

        assertTrue(decision.evidenceReady());
        assertEquals("observe_only", decision.patchCandidate());
        assertEquals("observe_only", decision.action());
        assertEquals("operator_stop", decision.policyDecision());
        assertFalse((TraceStore.getByPrefix("causalProbe.") + debugStore.list(10).toString())
                .contains("private operator stop reason"));
    }

    @Test
    void outOfScopeOrForbiddenCandidateFailsClosed() {
        Map<String, Object> trace = Map.of(
                "probe.sampleCount", 3,
                "web.brave.returnedCount", 4,
                "web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox = RagFailureBlackboxService.analyze(trace);

        CausalProbeTriggerService.Decision outOfScope = CausalProbeTriggerService.evaluate(
                "goal",
                trace,
                blackbox,
                List.of(),
                CausalProbeTriggerService.ProbeConstraints.allowOnly("cooldown_reorder"));
        CausalProbeTriggerService.Decision forbidden = CausalProbeTriggerService.evaluate(
                "goal",
                trace,
                blackbox,
                List.of(),
                new CausalProbeTriggerService.ProbeConstraints(
                        Set.of("anchor_compression_topup"),
                        Set.of("source_patch_candidate"),
                        false,
                        "none"));

        assertEquals("candidate_out_of_scope", outOfScope.policyDecision());
        assertEquals("observe_only", outOfScope.action());
        assertEquals("action_forbidden", forbidden.policyDecision());
        assertEquals("observe_only", forbidden.action());
    }

    @Test
    void refusesReadyWhenSampleCountIsTooLow() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 2);
        TraceStore.put("web.brave.returnedCount", 4);
        TraceStore.put("web.brave.afterFilterCount", 0);
        RagFailureBlackboxService.Snapshot blackbox =
                RagFailureBlackboxService.analyze(TraceStore.getAll());

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "sample-low-test",
                blackbox,
                List.of());

        assertFalse(decision.evidenceReady());
        assertEquals("sample_count_below_threshold", decision.triggerReason());
        assertEquals(Boolean.FALSE, TraceStore.get("causalProbe.evidenceReady"));
    }

    @Test
    void refusesReadyWhenOnlyOneSignalAxisPointsAtFailure() {
        CausalProbeTriggerService service = new CausalProbeTriggerService(
                provider(new DebugEventStore()), provider(null), provider(null));
        TraceStore.put("probe.sampleCount", 3);
        RagFailureBlackboxService.Snapshot blackbox =
                new RagFailureBlackboxService.Snapshot(
                        0.82d,
                        0.82d,
                        "timeout",
                        "web",
                        Map.of(),
                        List.of(),
                        "pattern",
                        "cooldown_reorder",
                        0.72d,
                        "unit",
                        Map.of(),
                        "SHADOW_REVIEW",
                        true);

        CausalProbeTriggerService.Decision decision = service.projectCurrentTrace(
                "goal",
                "one-axis-test",
                blackbox,
                List.of());

        assertFalse(decision.evidenceReady());
        assertEquals("axis_agreement_below_threshold", decision.triggerReason());
        assertEquals(Boolean.FALSE, TraceStore.get("causalProbe.evidenceReady"));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
