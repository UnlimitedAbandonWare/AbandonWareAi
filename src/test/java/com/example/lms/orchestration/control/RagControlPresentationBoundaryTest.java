package com.example.lms.orchestration.control;

import com.example.lms.search.TraceStore;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagControlPresentationBoundaryTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void nonRagAnswerIsByteForByteUnchanged() {
        RagControlPresentationBoundary boundary = boundary();

        assertEquals("semantic answer", boundary.project("semantic answer", false));
    }

    @Test
    void earlyRagReturnKeepsSemanticAnswerAndSeparateSevenStageDiagnostics() {
        RagControlRuntimeAdapter.capturePresentationInput(
                RagControlRuntimeAdapter.RuntimeInput.evidenceNeeded(true));

        String semanticAnswer = "### Answer\n\n| Symbol | Value |\n| --- | --- |\n| x | 2 |";
        RagControlPresentationBoundary.Projection projection =
                boundary().projectResult(semanticAnswer, true);

        assertEquals(semanticAnswer, projection.visibleAnswer());
        assertEquals(semanticAnswer, projection.persistableAnswer());
        assertFalse(projection.held());
        assertFalse(projection.visibleAnswer().contains(RagControlProjectionRenderer.TABLE_MARKER));
        String diagnostic = RagControlProjectionRenderer.renderProjection("", projection.plan());
        assertEquals(7, occurrences(diagnostic, "| EVIDENCE_NEEDED | OBSERVABILITY_GAP |"));
    }

    @Test
    void existingHardGuardSuppressesSemanticDraftEvenDuringShadowRollout() {
        String sentinel = "PRIVATE_UNVERIFIED_DRAFT";
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 1, 1, false, true, true, true));

        String visible = boundary().project(sentinel, true);

        assertFalse(visible.contains(sentinel));
        assertEquals(RagControlProjectionRenderer.heldNotice(), visible);
    }

    @Test
    void greetingWithoutVerificationRequirementPassesThroughDespiteMissingLineage() {
        // "안녕?" 시나리오: RAG 토글은 켜져 있지만 검증 불필요 응답은
        // runtime lineage 갭만으로 본문이 보류되지 않아야 한다.
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 0, 0, false, false, false, false, true, false));

        String greeting = "안녕하세요. 무엇을 도와드릴까요?";
        RagControlPresentationBoundary.Projection projection =
                boundary().projectResult(greeting, true);

        assertFalse(projection.held());
        assertEquals(greeting, projection.visibleAnswer());
        assertEquals(greeting, projection.persistableAnswer());
        assertFalse(projection.visibleAnswer().contains(RagControlProjectionRenderer.TABLE_MARKER));
    }

    @Test
    void verificationRequiredStillHoldsOnMissingLineage() {
        // 대조군: 검증이 필요한 응답은 lineage 누락 시 기존처럼 보류된다.
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 0, 0, false, false, false, false, true, true));

        RagControlPresentationBoundary.Projection projection =
                boundary().projectResult("unverified evidence claim", true);

        // SHADOW 모드에서는 관측만 하고 본문은 유지된다.
        assertFalse(projection.held());
        assertTrue(projection.plan() != null
                && projection.plan().action() == RagActionPlan.Action.HOLD);
    }

    @Test
    void enforcedHoldNeverReturnsSemanticTextAsPersistableState() {
        String sentinel = "PRIVATE_LINEAGE_GAP_DRAFT";
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 2, 1, false, true, true, false));

        RagControlPresentationBoundary.Projection projection =
                boundary(rollout, null, new RagControlProjectionRenderer())
                        .projectResult(sentinel, true);

        assertTrue(projection.held());
        assertFalse(projection.visibleAnswer().contains(sentinel));
        assertFalse(projection.persistableAnswer().contains(sentinel));
    }

    @Test
    void adapterFailureFailsClosedEvenDuringShadow() {
        String sentinel = "PRIVATE_ADAPTER_FAILURE_DRAFT";
        ModelRuntimeHealthTracker tracker = mock(ModelRuntimeHealthTracker.class);
        when(tracker.redactedRequestTimeline(nullable(String.class)))
                .thenThrow(new IllegalStateException("private-control-detail"));
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 2, 1, false, true, true, false));

        RagControlPresentationBoundary.Projection projection = boundary(
                new RagControlRolloutState(new RagControlProperties(20, 0.01d, 20)),
                tracker,
                new RagControlProjectionRenderer()).projectResult(sentinel, true);

        assertTrue(projection.held());
        assertFalse(projection.visibleAnswer().contains(sentinel));
        assertFalse(projection.persistableAnswer().contains(sentinel));
        // 어댑터 실패는 근거 부족이 아니라 제어-계층 장애 — 구분된 안전 문구를 기대한다.
        assertEquals(RagControlPresentationBoundary.CONTROL_FAILURE_NOTICE, projection.visibleAnswer());
        assertEquals(7, stageRowCount(RagControlProjectionRenderer.renderProjection("", projection.plan())));
        assertEquals(RagControlPresentationBoundary.CONTROL_FAILURE_NOTICE, projection.persistableAnswer());
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private-control-detail"));
    }

    @Test
    void nullPlanFailClosedKeepsHoldNoticeAndSeparateSevenSafeRows() {
        String sentinel = "PRIVATE_NULL_PLAN_DRAFT";

        RagControlPresentationBoundary.Projection projection =
                RagControlPresentationBoundary.Projection.failClosed(null);

        assertTrue(projection.held());
        assertEquals(RagActionPlan.Action.HOLD, projection.plan().action());
        assertTrue(projection.plan().enforced());
        assertEquals("control_projection_failed", projection.plan().reasonCode());
        assertFalse(projection.visibleAnswer().contains(sentinel));
        // null plan fail-closed도 제어-계층 장애 문구로 근거 부족과 구분한다.
        assertEquals(RagControlPresentationBoundary.CONTROL_FAILURE_NOTICE, projection.visibleAnswer());
        assertEquals(7, stageRowCount(RagControlProjectionRenderer.renderProjection("", projection.plan())));
        assertEquals(7, occurrences(
                RagControlProjectionRenderer.renderProjection("", projection.plan()),
                "| EVIDENCE_NEEDED | OBSERVABILITY_GAP |"));
        assertEquals(RagControlPresentationBoundary.CONTROL_FAILURE_NOTICE, projection.persistableAnswer());
    }

    @Test
    void measuredDisclosureLeakRollsBackAndReturnsFailClosedProjection() {
        String sentinel = "PRIVATE_RENDERER_LEAK_DRAFT";
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlProjectionRenderer leakingRenderer = mock(RagControlProjectionRenderer.class);
        when(leakingRenderer.append(anyString(), any(Boolean.class), any(RagActionPlan.class)))
                .thenReturn(sentinel);
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 1, 1, false, true, true, true));

        RagControlPresentationBoundary.Projection projection =
                boundary(rollout, null, leakingRenderer).projectResult(sentinel, true);

        assertTrue(projection.held());
        assertFalse(projection.visibleAnswer().contains(sentinel));
        assertEquals(RagControlRolloutState.Mode.SHADOW, rollout.mode());
    }

    @Test
    void stoppingRendererNeverReceivesSemanticDraftAndCannotHideItAfterNotice() {
        String sentinel = "PRIVATE_RENDERER_MIDDLE_LEAK_DRAFT";
        RagControlRolloutState rollout = new RagControlRolloutState(
                new RagControlProperties(1, 0.01d, 20));
        rollout.record(new RagControlRolloutState.Observation(true, false, false, false, 1));
        RagControlProjectionRenderer leakingRenderer = mock(RagControlProjectionRenderer.class);
        when(leakingRenderer.append(anyString(), any(Boolean.class), any(RagActionPlan.class)))
                .thenReturn(RagControlProjectionRenderer.heldNotice() + "\nnotice: " + sentinel);
        RagControlRuntimeAdapter.capturePresentationInput(new RagControlRuntimeAdapter.RuntimeInput(
                true, 1, 1, false, true, true, true));

        RagControlPresentationBoundary.Projection projection =
                boundary(rollout, null, leakingRenderer).projectResult(sentinel, true);

        ArgumentCaptor<String> rendererInput = ArgumentCaptor.forClass(String.class);
        verify(leakingRenderer).append(rendererInput.capture(), any(Boolean.class), any(RagActionPlan.class));
        assertEquals("", rendererInput.getValue());
        assertTrue(projection.held());
        assertFalse(projection.visibleAnswer().contains(sentinel));
        assertEquals(RagControlRolloutState.Mode.SHADOW, rollout.mode());
    }

    @Test
    void controlledTableTokenDoesNotCountAsHeldDraftDisclosure() {
        RagControlFinding hardHold = new RagControlFinding(
                "release-guard",
                RagControlFinding.Stage.PROMPT_EVIDENCE,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.HOLD,
                "existing_release_guard_hold",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                java.util.Map.of());
        RagActionPlan plan = new RagGuardProbeComposer().compose(java.util.List.of(hardHold));
        String visible = new RagControlProjectionRenderer().append("", true, plan);

        assertTrue(visible.contains("HOLD"));
        assertTrue(RagControlPresentationBoundary.safeHeldProjection(visible));
    }

    private static RagControlPresentationBoundary boundary() {
        return boundary(
                new RagControlRolloutState(new RagControlProperties(20, 0.01d, 20)),
                null,
                new RagControlProjectionRenderer());
    }

    private static RagControlPresentationBoundary boundary(
            RagControlRolloutState rollout,
            ModelRuntimeHealthTracker tracker,
            RagControlProjectionRenderer renderer) {
        RagControlCoordinator coordinator = new RagControlCoordinator(
                new RagGuardProbeComposer(), rollout);
        return new RagControlPresentationBoundary(
                coordinator,
                new RagControlRuntimeAdapter(),
                renderer,
                tracker);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int from = 0;
        while (value != null && token != null && !token.isEmpty()) {
            int next = value.indexOf(token, from);
            if (next < 0) {
                return count;
            }
            count++;
            from = next + token.length();
        }
        return count;
    }

    private static long stageRowCount(String value) {
        return value.lines()
                .filter(line -> java.util.List.of(RagControlFinding.Stage.values()).stream()
                        .anyMatch(stage -> line.startsWith("| " + stage.name() + " |")))
                .count();
    }
}
