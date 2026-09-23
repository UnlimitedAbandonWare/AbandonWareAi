package com.example.lms.service.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.energy.ContradictionScorer;

import dev.langchain4j.model.chat.ChatModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContradictionScorerPromptBoundaryTest {

    @org.junit.jupiter.api.AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void contradictionLlmCallUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/energy/ContradictionScorer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String contradictionScorePrompt ="));
        assertTrue(source.contains("UserMessage.from(contradictionScorePrompt)"));
    }

    @Test
    void contradictionLlmFailureUsesStableTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/energy/ContradictionScorer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\"contradiction_exception: \" + e.getClass().getSimpleName()"));
        assertTrue(source.contains("TraceStore.append(\"aux.failures\", \"contradiction_score_failed\")"));
    }

    @Test
    void contradictionLlmPathRequiresBreakerWhenEnabled() {
        ContradictionScorer scorer = new ContradictionScorer();
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(scorer, "useLlm", true);
        ReflectionTestUtils.setField(scorer, "chatModel", chatModel);
        ReflectionTestUtils.setField(scorer, "nightmareBreaker", null);

        double score = scorer.score("The budget is 10 dollars", "The budget is 20 dollars");

        assertEquals(1.0, score, 1.0e-9);
        assertEquals(Boolean.TRUE, TraceStore.get("contradiction.llm.breakerMissing"));
        assertEquals("heuristic_fallback", TraceStore.get("contradiction.llm.fallback"));
        verifyNoInteractions(chatModel);
    }

    @Test
    void contradictionNumericParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/energy/ContradictionScorer.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static double parseNumber0to1(String s)");
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
