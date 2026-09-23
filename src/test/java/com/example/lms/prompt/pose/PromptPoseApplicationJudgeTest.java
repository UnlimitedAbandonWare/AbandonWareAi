package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.learn.CfvmBanditStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptPoseApplicationJudgeTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void exploreIntentRaisesExploreLaneAndSearchBreadthWithoutRawQueryTrace() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);
        String raw = "PromptPose 응용을 더 과감하게 탐색하고 새로운 routing 아이디어를 brainstorm";
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(raw, props);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);
        PromptPoseTrace.writeApplicationDecision(decision, input);

        assertTrue(decision.enabled());
        assertEquals("explore", decision.intentSlot());
        assertEquals("exploratory", decision.evidenceSlot());
        assertTrue(decision.laneWeights().get("RC") > decision.laneWeights().get("BQ"));
        assertTrue(decision.queryBurstMax() >= 12);
        assertEquals(3, decision.selfAskCount());
        assertTrue(decision.selfAskTemperature() >= 0.40d);
        assertTrue(decision.minCitations() >= 2);
        assertEquals(Boolean.TRUE, TraceStore.get(PromptPoseTrace.APPLICATION_ENABLED));
        assertEquals("explore", TraceStore.get(PromptPoseTrace.APPLICATION_INTENT_SLOT));
        assertFalse(TraceStore.getAll().toString().contains(raw));
        assertEquals(Boolean.FALSE, TraceStore.get(PromptPoseTrace.RAW_INCLUDED));
    }

    @Test
    void evidenceSensitiveDebugAndComparePrecedeCreativeKeywords() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);

        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative answer with official evidence and citations", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "creative medical treatment ideas", props), 18).intentSlot());
        assertEquals("debug_patch", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative fix for this failure", props), 18).intentSlot());
        assertEquals("compare", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative comparison of option A vs option B", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm creative support for someone considering self-harm", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "자살 관련 아이디어를 과감하게 탐색해줘", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "개인 정보 유출 아이디어를 brainstorm", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "creative OAuth token exfiltration ideas", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "소송 전략 아이디어를 탐색해줘", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "creative prescription ideas", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm anxiety coping ideas", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "creative stalking scenario ideas", props), 18).intentSlot());
        assertEquals("evidence_strict", judge.decide(PromptPoseInputSanitizer.sanitize(
                "극단 선택을 과감하게 탐색해줘", props), 18).intentSlot());
    }

    @Test
    void latinEvidenceMarkersUseWordBoundariesInsteadOfSubstrings() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);

        assertEquals("explore", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative artifact exhibit", props), 18).intentSlot());
        assertEquals("explore", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative resourceful story", props), 18).intentSlot());
        assertEquals("explore", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative flawless visual", props), 18).intentSlot());
        assertEquals("explore", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative dispatch concept", props), 18).intentSlot());
        assertEquals("explore", judge.decide(PromptPoseInputSanitizer.sanitize(
                "brainstorm a creative prefix naming scheme", props), 18).intentSlot());
    }

    @Test
    void citationFailureSharpensStrictLaneAndCitationThreshold() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);
        TraceStore.put("blackbox.risk.dominantFailure", "insufficient_citations");
        TraceStore.put("guard.minCitations.actual", 1);
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "검증 기준을 날카롭게 잡고 근거와 citation을 보강해줘", props);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);

        assertTrue(decision.enabled());
        assertEquals("evidence_strict", decision.intentSlot());
        assertEquals("insufficient_citations", decision.failureSlot());
        assertTrue(decision.laneWeights().get("BQ") > decision.laneWeights().get("RC"));
        assertTrue(decision.minCitations() >= 4);
        assertEquals(3, decision.minLaneCoverage());
        assertTrue(decision.riskPenaltyLambda() >= 0.60d);
        assertTrue(decision.queryBurstMax() <= 10);
    }

    @Test
    void tavilySkippedReasonUsesProviderDisabledFailureSlot() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);
        TraceStore.put("web.tavily.skipped.reason", "provider_disabled");
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "debug prompt pose provider recovery", props);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);

        assertTrue(decision.enabled());
        assertEquals("provider_disabled", decision.failureSlot());
        assertTrue(decision.queryBurstMax() <= 8);
        assertTrue(decision.selfAskCount() <= 2);
    }

    @Test
    void genericNoneFailureDoesNotHideSpecificProviderFailure() {
        PromptPoseProperties props = enabledProps();
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, null);
        TraceStore.put("promptPose.failureClass", "none");
        TraceStore.put("web.tavily.skipped.reason", "provider_disabled");

        PromptPoseApplicationDecision decision = judge.decide(
                PromptPoseInputSanitizer.sanitize("ordinary recovery request", props), 18);

        assertEquals("provider_disabled", decision.failureSlot());
    }

    @Test
    void lowRewardFeedbackCorrectsNextRoutingTowardSharperVerification() {
        PromptPoseProperties props = enabledProps();
        CfvmBanditStore store = mock(CfvmBanditStore.class);
        CfvmBanditStore.TileStats tile = new CfvmBanditStore.TileStats();
        CfvmBanditStore.ArmStats arm = new CfvmBanditStore.ArmStats();
        arm.add(0.20d);
        tile.arms.put(PromptPoseArm.LOCAL_LIGHT.name(), arm);
        when(store.snapshot()).thenReturn(Map.of("promptPose:explore:none", tile));
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, store);
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "응용 탐색 아이디어를 넓게 찾아줘", props);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);

        assertEquals("low_reward", decision.feedbackSlot());
        assertEquals("explore:none", decision.feedbackTile());
        assertEquals(0.20d, decision.feedbackMean(), 1e-9);
        assertTrue(decision.laneWeights().get("BQ") > 1.0d);
        assertTrue(decision.minCitations() >= 3);
    }

    @Test
    void feedbackSnapshotFailureLeavesRedactedBreadcrumb() {
        PromptPoseProperties props = enabledProps();
        CfvmBanditStore store = mock(CfvmBanditStore.class);
        String rawSecret = "private-prompt-pose-feedback-token";
        when(store.snapshot()).thenThrow(new IllegalStateException(rawSecret));
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(props, store);
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "debug prompt pose feedback", props);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);

        assertEquals("none", decision.feedbackSlot());
        assertEquals(Boolean.TRUE, TraceStore.get("promptPose.application.feedbackSkipped"));
        assertEquals("cfvm_feedback_snapshot", TraceStore.get("promptPose.application.feedbackStage"));
        assertEquals("IllegalStateException", TraceStore.get("promptPose.application.feedbackErrorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawSecret));
    }

    private static PromptPoseProperties enabledProps() {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        return props;
    }
}
