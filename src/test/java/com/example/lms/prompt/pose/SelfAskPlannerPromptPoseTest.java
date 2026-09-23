package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.SelfAskPlanner;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SelfAskPlannerPromptPoseTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void exactCreativeProfileUsesRiskEffectiveTemperatureAboveLegacyCap() {
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("creative.emergence.selfAsk.effectiveTemperature", 0.91d);
        GuardContextHolder.set(context);
        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(
                "creative architecture", 100L, 0.2d, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));

        assertEquals(3, lanes.size());
        assertTrue(lanes.stream().allMatch(lane -> Double.valueOf(0.91d).equals(lane.meta.get("temperature"))));
    }

    @Test
    void partialProfileCannotUnlockCreativeSelfAsk() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.selfAsk.effectiveTemperature", 0.91d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        GuardContextHolder.set(context);
        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(
                "partial creative architecture", 100L, 0.90d,
                Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));

        assertTrue(lanes.stream().allMatch(
                lane -> ((Number) lane.meta.get("temperature")).doubleValue() <= 0.55d));
    }

    @Test
    void nonCreativeAndSensitiveRequestsRetainLegacyPointFiveFiveCap() {
        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        GuardContextHolder.set(new GuardContext());
        List<SelfAskPlanner.SubQuestion> ordinary = planner.generateThreeLanes(
                "ordinary query", 100L, 0.90d, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));

        assertTrue(ordinary.stream().allMatch(lane -> ((Number) lane.meta.get("temperature")).doubleValue() <= 0.55d));

        GuardContext sensitive = new GuardContext();
        sensitive.setSensitiveTopic(true);
        sensitive.putPlanOverride("creative.emergence.active", true);
        sensitive.putPlanOverride("creative.emergence.profile", "FERAL");
        sensitive.putPlanOverride("creative.emergence.selfAsk.temperature", 1.0d);
        sensitive.putPlanOverride("promptPose.application.intentSlot", "explore");
        GuardContextHolder.set(sensitive);
        List<SelfAskPlanner.SubQuestion> constrained = planner.generateThreeLanes(
                "sensitive query", 100L, 0.90d, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));

        assertTrue(constrained.stream().allMatch(lane -> ((Number) lane.meta.get("temperature")).doubleValue() <= 0.55d));
    }

    @Test
    void mandatorySelfAskCapBelowLegacyFloorAlwaysWins() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("llm.explore.temperature.max", 0.05d);
        GuardContextHolder.set(context);
        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);

        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes(
                "strict query", 100L, Double.NaN, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));

        assertTrue(lanes.stream().allMatch(lane -> Double.valueOf(0.05d).equals(lane.meta.get("temperature"))));
    }

    @Test
    void privacyOrMissingLineageCannotUnlockCreativeSelfAsk() {
        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        GuardContext context = completeWildCreativeContext();
        context.putPlanOverride("creative.emergence.selfAsk.effectiveTemperature", 0.91d);
        context.getPlanOverrides().remove("creative.emergence.requestedOptionsHash");
        GuardContextHolder.set(context);

        List<SelfAskPlanner.SubQuestion> missingHash = planner.generateThreeLanes(
                "creative architecture", 100L, 0.90d, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));
        assertTrue(missingHash.stream().allMatch(lane -> ((Number) lane.meta.get("temperature")).doubleValue() <= 0.55d));

        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("privacy.boundary.enforce", true);
        List<SelfAskPlanner.SubQuestion> privacy = planner.generateThreeLanes(
                "creative architecture", 100L, 0.90d, Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));
        assertTrue(privacy.stream().allMatch(lane -> ((Number) lane.meta.get("temperature")).doubleValue() <= 0.55d));
    }

    @Test
    void traceLaneWeightsApplyWhenCallerWeightsAreEmpty() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 1, 3, 3,
                Map.of("BQ", 0.25d, "ER", 1.0d, "RC", 2.5d),
                0.0d, 0.4d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 300L, Double.NaN, Map.of());

        assertEquals(3, lanes.size());
        assertEquals(Boolean.TRUE, TraceStore.get("selfask.promptPose.applied"));
        assertEquals("LOCAL_LIGHT", TraceStore.get("selfask.promptPose.arm"));
        assertTrue(lanes.stream().anyMatch(sq -> sq.type == SelfAskPlanner.SubQuestionType.RC
                && Double.valueOf(2.5d).equals(sq.meta.get("weight"))));
    }

    @Test
    void promptPoseArmTraceUsesSafeLabelWhenTraceStoreIsPolluted() {
        String rawArm = "LOCAL_LIGHT token=test-secret-abcdefghijklmnop";
        TraceStore.put(PromptPoseTrace.ARM, rawArm);

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        planner.generateThreeLanes("query", 1L);

        String stored = String.valueOf(TraceStore.get("selfask.promptPose.arm"));
        assertTrue(stored.startsWith("hash:"), stored);
        assertFalse(stored.contains("test-secret-abcdefghijklmnop"), stored);
        assertFalse(stored.contains(rawArm), stored);
    }

    @Test
    void selfAskCountLimitsSelectedLanesByPromptPoseWeightOrder() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 0, 0, 1,
                Map.of("BQ", 0.25d, "ER", 1.0d, "RC", 2.5d),
                0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 100L, Double.NaN, Map.of());

        assertEquals(1, lanes.size());
        assertEquals(SelfAskPlanner.SubQuestionType.RC, lanes.get(0).type);
        assertEquals(1, TraceStore.get("selfask.3way.laneLimit"));
        assertEquals(List.of("RC"), TraceStore.get("selfask.3way.laneOrder"));
    }

    @Test
    void explicitCallerLaneWeightsIgnorePromptPoseSelfAskCount() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(), List.of(), 0, 0, 1,
                Map.of("RC", 2.5d), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        SelfAskPlanner planner = new SelfAskPlanner(mock(ChatModel.class), null);
        List<SelfAskPlanner.SubQuestion> lanes = planner.generateThreeLanes("질문", 300L, Double.NaN,
                Map.of("BQ", 2.5d, "ER", 1.0d, "RC", 0.25d));

        assertEquals(3, lanes.size());
        assertEquals(3, TraceStore.get("selfask.3way.laneLimit"));
        assertEquals(List.of("BQ", "ER", "RC"), TraceStore.get("selfask.3way.laneOrder"));
    }

    private static GuardContext completeWildCreativeContext() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash", "hash:0123456789ab");
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        return context;
    }
}
