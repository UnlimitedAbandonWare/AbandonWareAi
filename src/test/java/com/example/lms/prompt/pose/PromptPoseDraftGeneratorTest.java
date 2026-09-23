package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptPoseDraftGeneratorTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void parsesValidJsonDraftPlan() throws Exception {
        PromptPoseDraftGenerator generator = new PromptPoseDraftGenerator(
                new PromptPoseProperties(), new ObjectMapper(), null, null);

        PromptPosePlan plan = generator.parseDraftJson("""
                {
                  "assistantDraftLines": ["prefer official docs"],
                  "queryBurstSeeds": ["site:docs example"],
                  "queryBurstMax": 4,
                  "selfAskCount": 2,
                  "laneWeights": {"BQ": 1.2, "RC": 1.5},
                  "selfAskTemperature": 0.3,
                  "confidence": 0.7
                }
                """, "llmrouter.light");

        assertEquals(PromptPoseArm.LOCAL_LIGHT, plan.arm());
        assertEquals(List.of("prefer official docs"), plan.assistantDraftLines());
        assertEquals(4, plan.queryBurstMax());
        assertEquals(2, plan.selfAskCount());
        assertEquals(1.5d, plan.laneWeights().get("RC"), 1e-9);
    }

    @Test
    void malformedJsonDoesNotPopulateRawTrace() {
        PromptPoseDraftGenerator generator = new PromptPoseDraftGenerator(
                new PromptPoseProperties(), new ObjectMapper(), null, null);

        assertThrows(Exception.class, () -> generator.parseDraftJson("not-json sk-secret", "llmrouter.light"));
        assertFalse(TraceStore.getAll().values().stream().anyMatch(v -> String.valueOf(v).contains("sk-secret")));
    }

    @Test
    void traceStoresSeedHashesOnly() {
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of("safe line"), List.of("sensitive raw seed"), 1, 3, 1,
                java.util.Map.of(), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "abc123", "ko", "general", 10));

        Object hashes = TraceStore.get(PromptPoseTrace.QUERY_BURST_SEED_HASHES);
        assertTrue(String.valueOf(hashes).matches(".*[0-9a-f]{12}.*"));
        assertFalse(String.valueOf(hashes).contains("sensitive raw seed"));
    }

    @Test
    void generateFailureLeavesRedactedBreadcrumb() {
        PromptPoseProperties props = new PromptPoseProperties();
        props.setEnabled(true);
        DynamicChatModelFactory factory = mock(DynamicChatModelFactory.class);
        String rawSensitive = "private prompt pose draft failure token";
        PromptBuilder throwingBuilder = (contexts, question) -> {
            throw new IllegalStateException(rawSensitive);
        };
        PromptPoseDraftGenerator generator = new PromptPoseDraftGenerator(
                props,
                new ObjectMapper(),
                provider(factory),
                provider(throwingBuilder));
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "debug prompt pose draft", props);

        PromptPosePlan plan = generator.generate(input);

        assertEquals(PromptPoseArm.NO_DRAFT, plan.arm());
        assertEquals("draft_failed", plan.reasonCode());
        assertEquals(Boolean.TRUE, TraceStore.get("promptPose.draft.skipped"));
        assertEquals("draft_generate", TraceStore.get("promptPose.draft.stage"));
        assertEquals("IllegalStateException", TraceStore.get("promptPose.draft.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawSensitive));
    }

    @Test
    void draftPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/prompt/pose/PromptPoseDraftGenerator.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String promptPoseDraftPrompt = promptBuilder.build(ctx);"));
        assertTrue(source.contains("UserMessage.from(promptPoseDraftPrompt)"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
