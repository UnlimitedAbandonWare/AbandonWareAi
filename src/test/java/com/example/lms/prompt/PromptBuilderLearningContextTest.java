package com.example.lms.prompt;

import com.example.lms.learning.chat.LearningActorRole;
import com.example.lms.learning.chat.LearningSignal;
import com.example.lms.learning.chat.LearningSignalKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderLearningContextTest {

    @Test
    void studentRoleRendersPersonalWeaknessPolicy() {
        PromptContext ctx = PromptContext.builder()
                .learningRole(LearningActorRole.STUDENT)
                .learningContextSummary("Training RAG memory: validatedSamples=2, feedbackItems=4")
                .learningSignals(List.of(new LearningSignal(LearningSignalKind.AUTOLEARN, "pendingLearningSamples", "pendingValidation=1", 0.25)))
                .build();

        String instructions = new StandardPromptBuilder().buildInstructions(ctx);

        assertTrue(instructions.contains("### RAG LEARNING SUPPORT CONTEXT"));
        assertTrue(instructions.contains("role: TRAINING_USER"));
        assertFalse(instructions.contains("role: STUDENT"));
        assertTrue(instructions.contains("internal personalization"));
        assertTrue(instructions.contains("not public citation evidence"));
        assertTrue(instructions.contains("user's own Training RAG history"));
        assertTrue(instructions.contains("### RAG LEARNING SUPPORT SIGNALS"));
        assertFalse(instructions.contains("### LMS CONTEXT SIGNALS"));
        assertFalse(instructions.contains("LMS/RAG context signals"));
        assertTrue(instructions.contains("AUTOLEARN"));
    }

    @Test
    void teacherAndAdminRolesRenderScopedPolicies() {
        StandardPromptBuilder builder = new StandardPromptBuilder();

        String teacher = builder.buildInstructions(PromptContext.builder()
                .learningRole(LearningActorRole.TEACHER)
                .learningSignals(List.of(new LearningSignal(LearningSignalKind.FEEDBACK, "trainingRagSupport", "feedbackThreads=2", 0.8)))
                .build());
        String admin = builder.buildInstructions(PromptContext.builder()
                .learningRole(LearningActorRole.ADMIN)
                .learningSignals(List.of(new LearningSignal(LearningSignalKind.RAG_OPS, "translationMemory", "pending=3", 0.9)))
                .build());

        assertTrue(teacher.contains("role: TRAINING_SUPPORT"));
        assertFalse(teacher.contains("role: TEACHER"));
        assertTrue(teacher.contains("assigned Training RAG support data"));
        assertFalse(teacher.contains("instructor's owned courses"));
        assertTrue(teacher.contains("FEEDBACK"));
        assertTrue(admin.contains("role: RAG_ADMIN"));
        assertFalse(admin.contains("role: ADMIN"));
        assertTrue(admin.contains("RAG quality"));
        assertTrue(admin.contains("raw PII"));
    }

    @Test
    void anonymousWithoutSignalsDoesNotAddLearningBlock() {
        String instructions = new StandardPromptBuilder().buildInstructions(PromptContext.builder().build());

        assertFalse(instructions.contains("### RAG LEARNING SUPPORT CONTEXT"));
    }
}
