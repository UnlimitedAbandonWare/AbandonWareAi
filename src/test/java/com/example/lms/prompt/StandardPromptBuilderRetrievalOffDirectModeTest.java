package com.example.lms.prompt;

import com.example.lms.domain.enums.AnswerMode;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardPromptBuilderRetrievalOffDirectModeTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @Test
    void retrievalOffWithoutEvidenceAllowsDirectAnswers() {
        PromptContext ctx = PromptContext.builder()
                .ragEnabled(false)
                .answerMode(AnswerMode.BALANCED)
                .build();

        String instructions = builder.buildInstructions(ctx);

        assertTrue(instructions.contains("### RETRIEVAL OFF DIRECT ANSWER MODE"), instructions);
        assertTrue(instructions.startsWith("### INSTRUCTIONS: Retrieval is OFF"), instructions);
        assertFalse(instructions.startsWith("### INSTRUCTIONS: Synthesize answers from sources"), instructions);
        assertTrue(instructions.contains("Empty SEARCH RESULTS are expected"), instructions);
        assertTrue(instructions.contains("answer directly"), instructions);
        assertTrue(instructions.contains("### DIRECT MODE STYLE OVERRIDE"), instructions);
        assertTrue(instructions.contains("return only that requested output"), instructions);
        assertTrue(instructions.contains("evidence_needed"), instructions);
    }

    @Test
    void retrievalOffSupportingDebugHeartbeatDoesNotDisableDirectMode() {
        PromptContext ctx = PromptContext.builder()
                .ragEnabled(false)
                .localDocs(List.of(Document.from("AGENT_VISIBLE_DEBUG_HEARTBEAT\nbrowser.status=OK")))
                .build();

        String instructions = builder.buildInstructions(ctx);

        assertTrue(instructions.contains("### RETRIEVAL OFF DIRECT ANSWER MODE"), instructions);
    }

    @Test
    void retrievalOffWithUserFacingLocalEvidenceKeepsEvidenceDiscipline() {
        PromptContext ctx = PromptContext.builder()
                .ragEnabled(false)
                .localDocs(List.of(Document.from("uploaded user note evidence")))
                .build();

        String instructions = builder.buildInstructions(ctx);

        assertFalse(instructions.contains("### RETRIEVAL OFF DIRECT ANSWER MODE"), instructions);
    }

    @Test
    void retrievalEnabledKeepsNormalEvidenceInstructions() {
        PromptContext ctx = PromptContext.builder()
                .ragEnabled(true)
                .build();

        String instructions = builder.buildInstructions(ctx);

        assertFalse(instructions.contains("### RETRIEVAL OFF DIRECT ANSWER MODE"), instructions);
    }
}
