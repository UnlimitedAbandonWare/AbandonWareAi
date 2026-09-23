package com.example.lms.orchestration.control;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagControlProjectionRendererTest {

    private final RagGuardProbeComposer composer = new RagGuardProbeComposer();
    private final RagControlProjectionRenderer renderer = new RagControlProjectionRenderer();

    @Test
    void directAnswerWithoutRagIsUnchanged() {
        RagActionPlan plan = composer.compose(List.of(retrievalFinding()));

        assertEquals("핵심 답변", renderer.append("핵심 답변", false, plan));
    }

    @Test
    void ragAnswerAlwaysGetsSevenOrderedStagesAndNeverInventsOk() {
        RagActionPlan plan = composer.compose(List.of(retrievalFinding()));

        String rendered = renderer.append("핵심 답변", true, plan);

        assertTrue(rendered.startsWith("핵심 답변"));
        assertTrue(rendered.contains("| 단계 | 상태 | 근거 수준 | 실패 분류 | 적용 조치 | 답변 영향 |"));
        int previous = -1;
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            int index = rendered.indexOf("| " + stage.name() + " |");
            assertTrue(index > previous, "stage must appear once in declared order: " + stage);
            previous = index;
        }
        long stageRows = rendered.lines()
                .filter(line -> List.of(RagControlFinding.Stage.values()).stream()
                        .anyMatch(stage -> line.startsWith("| " + stage.name() + " |")))
                .count();
        assertEquals(7, stageRows);
        assertTrue(rendered.contains("OBSERVABILITY_GAP"));
        assertTrue(rendered.contains("EVIDENCE_NEEDED"));
        assertFalse(rendered.contains("| OK |"));
        assertFalse(rendered.contains("PASS"));
    }

    @Test
    void projectionNeverIncludesRawEvidenceOrReasonText() {
        RagControlFinding hostile = new RagControlFinding(
                "probe|bad\nrow",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.ZERO_RESULT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.PROBE,
                RagActionPlan.Action.ISOLATE_EVIDENCE,
                "unsafe|reason\nrow",
                "private-request-id",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of("authorization", "restricted-marker-never-render-this"));

        String rendered = renderer.append("핵심 답변", true, composer.compose(List.of(hostile)));

        assertFalse(rendered.contains("restricted-marker-never-render-this"));
        assertFalse(rendered.contains("unsafe|reason"));
        assertFalse(rendered.contains("probe|bad"));
        assertFalse(rendered.contains("private-request-id"));
    }

    @Test
    void enforcedHoldSuppressesTheSemanticAnswerBeforeRendering() {
        String sentinel = "SENTINEL_ANSWER_MUST_NOT_SURVIVE";
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
                Map.of());
        RagActionPlan plan = composer.compose(List.of(hardHold));

        String rendered = renderer.append(sentinel, true, plan);

        assertFalse(rendered.contains(sentinel));
        assertTrue(rendered.contains(RagControlProjectionRenderer.TABLE_MARKER));
    }

    @Test
    void enforcedBlockKeepsDiagnosticsSeparateAndPublicProjectionOnlyNotice() {
        String sentinel = "SENTINEL_BLOCK_DRAFT_MUST_NOT_SURVIVE";
        RagControlFinding hardBlock = new RagControlFinding(
                "policy-guard",
                RagControlFinding.Stage.PROMPT_EVIDENCE,
                RagControlFinding.FailureClass.POLICY_DENIED,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.BLOCK,
                "policy_block",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of());
        RagActionPlan plan = composer.compose(List.of(hardBlock));

        String rendered = renderer.append(sentinel, true, plan);
        RagControlPresentationBoundary.Projection projection =
                RagControlPresentationBoundary.Projection.failClosed(plan);

        assertTrue(plan.shouldStop());
        assertEquals(RagActionPlan.Action.BLOCK, projection.plan().action());
        assertEquals("policy_block", projection.plan().reasonCode());
        assertFalse(rendered.contains(sentinel));
        assertFalse(projection.visibleAnswer().contains(sentinel));
        assertEquals(RagControlProjectionRenderer.heldNotice(), projection.persistableAnswer());
        assertEquals(RagControlProjectionRenderer.heldNotice(), projection.visibleAnswer());
        assertEquals(1, occurrences(rendered, RagControlProjectionRenderer.TABLE_MARKER));
        assertEquals(7, rendered.lines()
                .filter(line -> List.of(RagControlFinding.Stage.values()).stream()
                        .anyMatch(stage -> line.startsWith("| " + stage.name() + " |")))
                .count());
    }

    @Test
    void renderingTheSameAnswerTwiceKeepsExactlyOneProjection() {
        RagActionPlan plan = composer.compose(List.of(retrievalFinding()));

        String once = renderer.append("semantic answer", true, plan);
        String twice = renderer.append(once, true, plan);

        assertEquals(1, occurrences(twice, RagControlProjectionRenderer.TABLE_MARKER));
        assertEquals(7, twice.lines()
                .filter(line -> List.of(RagControlFinding.Stage.values()).stream()
                        .anyMatch(stage -> line.startsWith("| " + stage.name() + " |")))
                .count());
    }

    @Test
    void enforcedNonTerminalActionIsReportedAsObservationOnlyWithoutReceipt() {
        RagActionPlan plan = new RagActionPlan(
                RagActionPlan.Action.DEGRADE,
                false,
                true,
                false,
                false,
                "zero_result",
                List.of(retrievalFinding()),
                RagControlRolloutState.Mode.ENFORCE,
                1L,
                true);

        String rendered = renderer.append("semantic answer", true, plan);

        assertTrue(rendered.startsWith("semantic answer"));
        assertTrue(rendered.contains("OBSERVED_ONLY(DEGRADE)"));
        assertTrue(rendered.contains("execution receipt missing"));
        assertFalse(rendered.contains("| DEGRADED |"));
    }

    @Test
    void terminalRowShowsTheFindingThatActuallySelectedThePlan() {
        ArrayList<RagControlFinding> findings = new ArrayList<>();
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            if (stage == RagControlFinding.Stage.RETRIEVAL) {
                continue;
            }
            findings.add(new RagControlFinding(
                    "healthy-" + stage.name().toLowerCase(),
                    stage,
                    RagControlFinding.FailureClass.NONE,
                    RagControlFinding.EvidenceStatus.VERIFIED,
                    RagControlFinding.Authority.DIAGNOSTIC,
                    RagActionPlan.Action.CONTINUE,
                    "verified_" + stage.name().toLowerCase(),
                    "hash:0123456789ab",
                    RagControlFinding.LineageStatus.COMPLETE,
                    Map.of()));
        }
        findings.add(new RagControlFinding(
                "non-locking-hard-guard",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.NONE,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.HARD_GUARD,
                RagActionPlan.Action.CONTINUE,
                "hard_guard_continue",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of()));
        findings.add(new RagControlFinding(
                "verification-stop",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.CITATION_MISS,
                RagControlFinding.EvidenceStatus.VERIFIED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                "verification_rejected",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of()));
        RagActionPlan plan = composer.compose(findings)
                .withRollout(RagControlRolloutState.Mode.ENFORCE, true);

        String rendered = renderer.append("semantic answer", true, plan);
        String retrievalRow = rendered.lines()
                .filter(line -> line.startsWith("| RETRIEVAL |"))
                .findFirst()
                .orElseThrow();

        assertEquals(RagActionPlan.Action.HOLD, plan.action());
        assertTrue(plan.shouldStop());
        assertTrue(retrievalRow.contains("| HELD | VERIFIED | CITATION_MISS | HOLD |"));
        assertFalse(retrievalRow.contains("CONTINUE"));
    }

    private static RagControlFinding retrievalFinding() {
        return new RagControlFinding(
                "retrieval-stage",
                RagControlFinding.Stage.RETRIEVAL,
                RagControlFinding.FailureClass.ZERO_RESULT,
                RagControlFinding.EvidenceStatus.OBSERVED,
                RagControlFinding.Authority.VERIFICATION,
                RagActionPlan.Action.DEGRADE,
                "zero_result",
                "hash:0123456789ab",
                RagControlFinding.LineageStatus.COMPLETE,
                Map.of("returnedCount", 0));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while (text != null && token != null && !token.isEmpty()) {
            int next = text.indexOf(token, from);
            if (next < 0) {
                return count;
            }
            count++;
            from = next + token.length();
        }
        return count;
    }

}
