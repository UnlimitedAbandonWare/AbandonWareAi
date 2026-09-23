package com.example.lms.prompt;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderBoundaryTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void chatWorkflowFinalAnswerPathUsesPromptBuilderBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/ChatWorkflow.java"));

        assertTrue(source.contains("promptBuilder.build(ctx)"));
        assertTrue(source.contains("promptBuilder.buildInstructions(ctx)"));
        assertTrue(source.contains("TraceStore.put(\"chatWorkflow.promptBuilderUsed\", true);"));
        assertTrue(source.contains(".contextRefinementSummary(promptComposition == null ? null : promptComposition.contextRefinementSummary())"));
        assertTrue(source.contains(".contextRefinementSignals(promptComposition == null ? null : promptComposition.contextRefinementSignals())"));
        String refinerCall = "sampleCandidatesForRefinement(refinerSeedCtx, sessionIdLong,";
        assertTrue(source.contains(refinerCall));
        assertTrue(source.contains("() -> throwIfCancelled(sessionIdLong)"));
        assertTrue(source.indexOf(refinerCall)
                < source.indexOf("String ctxText = promptBuilder.build(ctx);"));
        assertTrue(source.contains("ctxBuilder.ensembleCandidates(refinementCandidates)"));
        assertTrue(source.contains("Math.min(3, refinementCandidates.size())"));
        assertTrue(source.contains("TraceStore.get(\"ensemble.refiner.selectionDecision\")"));
        assertFalse(source.contains("refinementCandidates.get(0).nodeId()"));
        assertFalse(source.contains("String finalPrompt ="));
        assertFalse(source.contains("new StringBuilder(\"### SEARCH RESULTS"));
        assertFalse(source.contains("String.format(WEB_PREFIX"));
        assertFalse(source.contains("String.format(RAG_PREFIX"));
        assertFalse(source.contains("WEB_PREFIX.formatted"));
        assertFalse(source.contains("RAG_PREFIX.formatted"));
        assertFalse(source.contains("RAG_PREFIX"));
        assertFalse(source.contains("WEB_PREFIX"));
        assertFalse(source.contains("MEM_PREFIX"));
        assertFalse(source.contains("LEGACY_RAG_PROMPT_REMOVED"));
    }

    @Test
    void chatApiControllerKeepsAttachmentEvidenceOnPromptContextPath() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(source.contains("ChatWorkflow -> PromptContext.localDocs -> PromptBuilder.build(ctx)"));
        assertFalse(source.contains("String finalPrompt ="));
    }

    @Test
    void singleContextBuildRejectsNullContextWithTraceBreadcrumb() {
        PromptBuilder builder = new StandardPromptBuilder();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> builder.build((PromptContext) null));

        assertTrue(error.getMessage().contains("prompt context"));
        assertEquals(true, TraceStore.get("promptBuilder.nullCtx"));
    }
}
