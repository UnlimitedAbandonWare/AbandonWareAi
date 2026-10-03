package com.example.lms.service.understanding;

import com.example.lms.learning.gemini.GeminiClient;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnderstandingTypedOutcomeTest {
    GeminiClient client;
    AnswerUnderstandingService service;
    @BeforeEach void setup() {
        client = mock(GeminiClient.class);
        var prompt = mock(AnswerUnderstandingPromptBuilder.class);
        when(prompt.build(anyString(), anyString())).thenReturn("synthetic prompt");
        service = new AnswerUnderstandingService(client, prompt);
        ReflectionTestUtils.setField(service, "understandingEnabled", true);
        ReflectionTestUtils.setField(service, "timeoutMs", 500L);
        ReflectionTestUtils.setField(service, "model", "synthetic-config");
    }
    @AfterEach void cleanup() { Thread.interrupted(); com.example.lms.search.TraceStore.clear(); }
    @Test void providerResultHasItsOwnOutcome() {
        when(client.generate(anyString())).thenReturn(Mono.just("{\"tldr\":\"summary\",\"confidence\":0.9}"));
        var result = service.understandDerived("answer", "question", 500, "synthetic-config");
        assertEquals(AnswerUnderstandingService.OutcomeKind.PROVIDER, result.kind());
        assertEquals("summary", result.value().tldr());
        verify(client, times(1)).generate(anyString());
    }
    @Test void fallbackNeverCountsAsProviderSuccess() {
        when(client.generate(anyString())).thenReturn(Mono.error(new IllegalStateException("synthetic")));
        var result = service.understandDerived("answer.", "question", 500, "synthetic-config");
        assertEquals(AnswerUnderstandingService.OutcomeKind.HEURISTIC_FALLBACK, result.kind());
        assertNotNull(result.value());
    }
    @Test void exhaustedBudgetIsSkippedAndNotPersistable() {
        var result = service.understandDerived("answer", "question", 0, "synthetic-config");
        assertEquals(AnswerUnderstandingService.OutcomeKind.SKIPPED, result.kind());
        assertNull(result.value()); verifyNoInteractions(client);
    }
    @Test void disabledOrChangedConfigurationMakesNoProviderCall() {
        assertEquals(AnswerUnderstandingService.OutcomeKind.SKIPPED,
                service.understandDerived("answer", "question", 500, "old-config").kind());
        ReflectionTestUtils.setField(service, "understandingEnabled", false);
        assertEquals(AnswerUnderstandingService.OutcomeKind.SKIPPED,
                service.understandDerived("answer", "question", 500, "synthetic-config").kind());
        assertNotNull(service.understand("answer", "question"), "F01-A disabled fallback stays compatible");
        verifyNoInteractions(client);
    }
    @Test void cancellationIsNotConvertedIntoFallbackSuccess() {
        Thread.currentThread().interrupt();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> service.understandDerived("answer", "question", 500, "synthetic-config"));
        verifyNoInteractions(client);
    }
    @Test void unexpectedBoundaryFailureIsExplicitAndHasNoValue() {
        var broken = spy(service);
        doThrow(new IllegalStateException("synthetic")).when(broken).understandOutcome("answer", "question", 500);
        var result = broken.understandDerived("answer", "question", 500, "synthetic-config");
        assertEquals(AnswerUnderstandingService.OutcomeKind.FAILURE, result.kind());
        assertNull(result.value()); verifyNoInteractions(client);
    }
}
