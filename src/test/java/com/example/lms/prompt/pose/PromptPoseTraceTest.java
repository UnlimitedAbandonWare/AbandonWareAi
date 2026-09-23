package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPoseTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void noDraftPlanClearsPreviousOptionalHintsAndDisablesAccessors() {
        PromptPoseTrace.writePlan(new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                        List.of("safe line"), List.of("safe seed"), 1, 4, 2,
                        Map.of("RC", 2.0d), 0.0d, 0.35d, 0, 0.8d, "ok"),
                input());

        assertEquals(2, PromptPoseTrace.selfAskCount());
        assertEquals(4, PromptPoseTrace.queryBurstCap());
        assertFalse(PromptPoseTrace.laneWeights().isEmpty());
        assertFalse(PromptPoseTrace.queryBurstSeedHashes().isEmpty());
        assertEquals(0.35d, PromptPoseTrace.selfAskTemperature(), 1e-9);
        TraceStore.put(PromptPoseTrace.REWARD_ARM, PromptPoseArm.LOCAL_LIGHT.name());
        TraceStore.put(PromptPoseTrace.REWARD_TILE_KEY, "tile-a");
        TraceStore.put(PromptPoseTrace.REWARD_VALUE, 0.7d);

        PromptPoseTrace.writePlan(PromptPosePlan.noDraft("llmrouter.light", "draft_disabled"), input());

        assertNull(PromptPoseTrace.selfAskCount());
        assertNull(PromptPoseTrace.queryBurstCap());
        assertTrue(PromptPoseTrace.laneWeights().isEmpty());
        assertTrue(PromptPoseTrace.queryBurstSeedHashes().isEmpty());
        assertNull(PromptPoseTrace.selfAskTemperature());
        assertEquals(PromptPoseArm.NO_DRAFT.name(), PromptPoseTrace.arm());
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_ARM));
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_TILE_KEY));
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_VALUE));
    }

    @Test
    void disabledTraceClearsPreviousOptionalHintsAndDisablesAccessors() {
        PromptPoseTrace.writePlan(new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                        List.of("safe line"), List.of("safe seed"), 1, 4, 2,
                        Map.of("BQ", 1.4d), 0.0d, 0.25d, 0, 0.8d, "ok"),
                input());
        TraceStore.put(PromptPoseTrace.REWARD_ARM, PromptPoseArm.LOCAL_LIGHT.name());
        TraceStore.put(PromptPoseTrace.REWARD_TILE_KEY, "tile-a");
        TraceStore.put(PromptPoseTrace.REWARD_VALUE, 0.7d);

        PromptPoseTrace.writeDisabled("disabled", input());

        assertNull(PromptPoseTrace.selfAskCount());
        assertNull(PromptPoseTrace.queryBurstCap());
        assertTrue(PromptPoseTrace.laneWeights().isEmpty());
        assertTrue(PromptPoseTrace.queryBurstSeedHashes().isEmpty());
        assertNull(PromptPoseTrace.selfAskTemperature());
        assertEquals(Boolean.FALSE, TraceStore.get(PromptPoseTrace.ENABLED));
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_ARM));
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_TILE_KEY));
        assertNull(TraceStore.get(PromptPoseTrace.REWARD_VALUE));
    }

    @Test
    void disabledTraceReasonDoesNotStoreRawSensitiveText() {
        String rawSecret = "" + com.example.lms.test.SecretFixtures.openAiKey() + "";

        PromptPoseTrace.writeDisabled("private prompt pose reason owner-token=" + rawSecret, input());

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawSecret), trace);
        assertFalse(String.valueOf(TraceStore.get(PromptPoseTrace.SKIP_REASON)).contains("private prompt pose reason"));
        assertTrue(String.valueOf(TraceStore.get(PromptPoseTrace.SKIP_REASON)).startsWith("hash:"));
        assertEquals(Boolean.FALSE, TraceStore.get(PromptPoseTrace.RAW_INCLUDED));
        assertEquals("privacy-block", TraceStore.get(PromptPoseTrace.FAILURE_CLASS));
    }

    @Test
    void laneWeightAccessorDropsNonFiniteTraceValues() {
        PromptPoseTrace.writePlan(new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                        List.of("safe line"), List.of("safe seed"), 1, 4, 2,
                        Map.of("RC", Double.NaN, "BQ", 1.0d), 0.0d, 0.35d, 0, 0.8d, "ok"),
                input());

        Map<String, Double> weights = PromptPoseTrace.laneWeights();

        assertFalse(weights.containsKey("RC"));
        assertEquals(1.0d, weights.get("BQ"));
    }

    @Test
    void traceFallbacksKeepScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/prompt/pose/PromptPoseTrace.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"trace_put\""));
        assertTrue(source.contains("traceSkipped(\"double_parse\""));
        assertTrue(source.contains("traceSkipped(\"int_parse\""));
        assertTrue(source.contains("[AWX][prompt][pose] trace skipped"));
    }

    private static PromptPoseInputSanitizer.SanitizedInput input() {
        return new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "abc123abc123", "ko", "general", 10);
    }
}
