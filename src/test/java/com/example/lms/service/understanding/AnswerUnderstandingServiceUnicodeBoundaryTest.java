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

    @Test
    void expiredRequestBudgetSkipsGenerationAndDoesNotPretendSummaryWasPrepared() {
        FallbackFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.service(), "understandingEnabled", true);
        ReflectionTestUtils.setField(fixture.service(), "timeoutMs", 12000L);
        org.mockito.Mockito.when(fixture.promptBuilder().build("question", "answer")).thenReturn("synthetic");
        org.mockito.Mockito.when(fixture.geminiClient().generate("synthetic"))
                .thenReturn(reactor.core.publisher.Mono.just("{\"tldr\":\"summary\",\"confidence\":0.9}"));
        var budget = new com.abandonware.ai.addons.budget.TimeBudget(1000);
        budget.cancel();
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(budget);
        try {
            org.junit.jupiter.api.Assertions.assertNull(fixture.service().understand("answer", "question"));
            verifyNoInteractions(fixture.geminiClient(), fixture.promptBuilder());
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            com.example.lms.search.TraceStore.clear();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"caller", "configured", "request"})
    void shortestAllowanceBoundsAndDisposesSummaryWait(String limiter) {
        FallbackFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.service(), "understandingEnabled", true);
        ReflectionTestUtils.setField(fixture.service(), "timeoutMs", limiter.equals("configured") ? 60L : 12000L);
        org.mockito.Mockito.when(fixture.promptBuilder().build("question", "answer")).thenReturn("synthetic");
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.when(fixture.geminiClient().generate("synthetic"))
                .thenReturn(reactor.core.publisher.Mono.<String>never().doOnCancel(() -> cancelled.set(true)));
        if (limiter.equals("request"))
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(200));
        try {
            var summary = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> {
                        // The request budget is thread-local; bind the same budget on the timed worker.
                        if (limiter.equals("request"))
                            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(200));
                        try { return fixture.service().understand("answer", "question", limiter.equals("caller") ? 60L : 12000L); }
                        finally { com.abandonware.ai.addons.budget.TimeBudgetContext.clear(); com.example.lms.search.TraceStore.clear(); }
                    });
            assertEquals("answer", summary.tldr());
            org.junit.jupiter.api.Assertions.assertTrue(cancelled.get(), "timed-out subscription must be disposed");
            org.mockito.Mockito.verify(fixture.geminiClient()).generate("synthetic");
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
        }
    }

    @Test
    void zeroCallerAllowanceNeverStartsSummaryGeneration() {
        FallbackFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.service(), "understandingEnabled", true);
        ReflectionTestUtils.setField(fixture.service(), "timeoutMs", 12000L);
        try {
            org.junit.jupiter.api.Assertions.assertNull(fixture.service().understand("answer", "question", 0));
            verifyNoInteractions(fixture.geminiClient(), fixture.promptBuilder());
            assertEquals("skipped", com.example.lms.search.TraceStore.get("understanding.status"));
        } finally { com.example.lms.search.TraceStore.clear(); }
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
