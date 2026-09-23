package com.example.lms.prompt.pose;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.StandardPromptBuilder;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderPromptPoseBoundaryTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void finalPromptBuilderDoesNotReceiveRawPromptPoseDraft() {
        String rawDraft = "RAW_PROMPT_POSE_DRAFT_SHOULD_NOT_BE_IN_FINAL_PROMPT";
        PromptPosePlan plan = new PromptPosePlan(true, PromptPoseArm.LOCAL_LIGHT, "llmrouter.light",
                List.of(rawDraft), List.of(), 1, 2, 1,
                Map.of(), 0.0d, 0.0d, 0, 0.8d, "ok");
        PromptPoseTrace.writePlan(plan, new PromptPoseInputSanitizer.SanitizedInput(
                false, "", "preview", "hashhashhash", "ko", "general", 10));

        StandardPromptBuilder builder = new StandardPromptBuilder();
        String prompt = builder.build(PromptContext.builder()
                .systemInstruction("Answer from evidence only.")
                .userQuery("user question")
                .build());

        assertFalse(prompt.contains(rawDraft));
        assertTrue(prompt.contains("user question"));
    }
}
