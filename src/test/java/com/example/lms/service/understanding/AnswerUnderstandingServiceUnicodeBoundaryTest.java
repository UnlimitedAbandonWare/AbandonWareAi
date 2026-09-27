package com.example.lms.service.understanding;

import com.example.lms.dto.answer.AnswerUnderstanding;
import com.example.lms.learning.gemini.GeminiClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AnswerUnderstandingServiceUnicodeBoundaryTest {

    @Test
    void fallbackTldrDoesNotSplitSupplementaryCharacter() {
        String answer = "x".repeat(199) + "\uD83D\uDE00" + "tail";
        FallbackFixture fixture = fixture();

        AnswerUnderstanding understanding = fixture.service().understand(answer, "question");

        assertEquals("x".repeat(199), understanding.tldr());
        assertFalse(hasUnpairedSurrogate(understanding.tldr()), understanding.tldr());
        assertEquals(List.of(answer), understanding.keyPoints());
        verifyNoInteractions(fixture.geminiClient(), fixture.promptBuilder());
    }

    @Test
    void fallbackTldrKeepsBmpAndCompletePairControls() {
        FallbackFixture fixture = fixture();
        assertEquals("y".repeat(200), fixture.service().understand("y".repeat(201), "question").tldr());
        String completePair = "q".repeat(198) + "\uD83D\uDE00";
        assertEquals(completePair, fixture.service().understand(completePair + "tail", "question").tldr());
        verifyNoInteractions(fixture.geminiClient(), fixture.promptBuilder());
    }

    @Test
    void fallbackPunctuationBranchKeepsFirstSentenceContract() {
        FallbackFixture fixture = fixture();
        String answer = "first." + "p".repeat(220);

        AnswerUnderstanding understanding = fixture.service().understand(answer, "question");

        assertEquals("first.", understanding.tldr());
        assertEquals(List.of(answer), understanding.keyPoints());
        verifyNoInteractions(fixture.geminiClient(), fixture.promptBuilder());
    }

    private static FallbackFixture fixture() {
        GeminiClient geminiClient = mock(GeminiClient.class);
        AnswerUnderstandingPromptBuilder promptBuilder = mock(AnswerUnderstandingPromptBuilder.class);
        AnswerUnderstandingService service = new AnswerUnderstandingService(geminiClient, promptBuilder);
        ReflectionTestUtils.setField(service, "understandingEnabled", false);
        return new FallbackFixture(service, geminiClient, promptBuilder);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private record FallbackFixture(
            AnswerUnderstandingService service,
            GeminiClient geminiClient,
            AnswerUnderstandingPromptBuilder promptBuilder) {
    }
}
