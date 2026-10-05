package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmResponseTerminalException;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.verification.ClaimVerifierService;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.SourceAnalyzerService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Offline, synthetic provider failures only. These tests do not establish that
 * a particular production request received an acknowledged user cancellation.
 */
class VerifierCancellationBoundaryTest {
    private static final String QUESTION = "synthetic fixture status";
    private static final String CONTEXT = "The synthetic fixture status is amber. ".repeat(8);
    private static final String DRAFT = "The synthetic fixture status is amber.";
    private static final String MODEL = "synthetic-verifier-model";

    @BeforeEach
    void clearBefore() {
        clearThreadContext();
    }

    @AfterEach
    void clearAfter() {
        clearThreadContext();
    }

    private static void clearThreadContext() {
        Thread.interrupted();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @ParameterizedTest(name = "fact verifier: {0}")
    @MethodSource("cancellations")
    void factVerifierPropagatesCancellationBeforeDownstreamJudges(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());
        FactFixture fixture = factFixture(model);

        assertThrows(CancellationException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertEquals(1, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
        assertEquals(failure.interruptExpected(), Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "claim verifier: {0}")
    @MethodSource("cancellations")
    void claimVerifierPropagatesCancellationInsteadOfUnknownResult(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());
        ClaimVerifierService verifier = claimVerifier(model);

        assertThrows(CancellationException.class, () -> verifier.verifyClaims(CONTEXT, DRAFT, MODEL));

        assertEquals(1, model.calls(), "cancelled extraction must not start claim judgment");
        assertNotEquals("judge_call_failed", TraceStore.get("claimVerifier.judge.disabledReason"));
        assertEquals(failure.interruptExpected(), Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "fact classifier: {0}")
    @MethodSource("cancellations")
    void factClassifierPropagatesCancellationInsteadOfHeuristicResult(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());
        FactStatusClassifier classifier = classifier(model);

        assertThrows(CancellationException.class, () -> classifier.classify(
                QUESTION, CONTEXT, DRAFT, MODEL));

        assertEquals(1, model.calls());
        assertNotEquals("judge_call_failed", TraceStore.get("factStatusClassifier.judge.disabledReason"));
        assertEquals(failure.interruptExpected(), Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "fact verifier ordinary failure: {0}")
    @MethodSource("ordinaryFailures")
    void ordinaryFactVerifierFailureRemainsUnknown(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());
        FactFixture fixture = factFixture(model);

        var result = fixture.verifier().verifyDetailed(QUESTION, CONTEXT, "", DRAFT, MODEL, false);

        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        assertEquals(1, model.calls());
        verify(fixture.classifier()).classify(QUESTION, CONTEXT, DRAFT, MODEL);
        verify(fixture.claims()).verifyClaims(CONTEXT, DRAFT, MODEL);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "claim verifier ordinary failure: {0}")
    @MethodSource("ordinaryFailures")
    void ordinaryClaimFailureRemainsUnknown(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());

        var result = claimVerifier(model).verifyClaims(CONTEXT, DRAFT, MODEL);

        assertEquals(DRAFT, result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        assertEquals(1, model.calls());
        assertEquals("judge_call_failed", TraceStore.get("claimVerifier.judge.disabledReason"));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "fact classifier ordinary failure: {0}")
    @MethodSource("ordinaryFailures")
    void ordinaryClassifierFailureRetainsItsHeuristicFallback(FailureCase failure) {
        FailingModel model = new FailingModel(failure.failure().get());

        var result = classifier(model).classify(QUESTION, CONTEXT, DRAFT, MODEL);

        // This is the classifier's existing heuristic, not final answer approval.
        assertEquals(FactVerificationStatus.PASS, result);
        assertEquals(1, model.calls());
        assertEquals("judge_call_failed", TraceStore.get("factStatusClassifier.judge.disabledReason"));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "fact verifier terminal wrapped={0}")
    @ValueSource(booleans = {false, true})
    void factVerifierStopsWithoutPublishingAuxiliaryTerminal(boolean wrapped) {
        LlmResponseTerminalException terminal = terminal();
        FailingModel model = new FailingModel(wrapped ? new RuntimeException("synthetic wrapper", terminal) : terminal);
        FactFixture fixture = factFixture(model);

        var thrown = assertThrows(LlmResponseTerminalException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertSanitizedTerminal(thrown);
        assertEquals(1, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "claim verifier terminal wrapped={0}")
    @ValueSource(booleans = {false, true})
    void claimVerifierStopsWithoutPublishingAuxiliaryTerminal(boolean wrapped) {
        LlmResponseTerminalException terminal = terminal();
        FailingModel model = new FailingModel(wrapped ? new RuntimeException("synthetic wrapper", terminal) : terminal);

        var thrown = assertThrows(LlmResponseTerminalException.class,
                () -> claimVerifier(model).verifyClaims(CONTEXT, DRAFT, MODEL));

        assertSanitizedTerminal(thrown);
        assertEquals(1, model.calls());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @ParameterizedTest(name = "fact classifier terminal wrapped={0}")
    @ValueSource(booleans = {false, true})
    void factClassifierStopsWithoutPublishingAuxiliaryTerminal(boolean wrapped) {
        LlmResponseTerminalException terminal = terminal();
        FailingModel model = new FailingModel(wrapped ? new RuntimeException("synthetic wrapper", terminal) : terminal);

        var thrown = assertThrows(LlmResponseTerminalException.class,
                () -> classifier(model).classify(QUESTION, CONTEXT, DRAFT, MODEL));

        assertSanitizedTerminal(thrown);
        assertEquals(1, model.calls());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void alreadyInterruptedFactVerifierDoesNotInvokeProviderOrDownstreamJudges() {
        FailingModel model = new FailingModel(new IllegalStateException("synthetic forbidden provider call"));
        FactFixture fixture = factFixture(model);
        Thread.currentThread().interrupt();

        assertThrows(CancellationException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertEquals(0, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void alreadyInterruptedClaimVerifierDoesNotInvokeProvider() {
        FailingModel model = new FailingModel(new IllegalStateException("synthetic forbidden provider call"));
        ClaimVerifierService verifier = claimVerifier(model);
        Thread.currentThread().interrupt();

        assertThrows(CancellationException.class, () -> verifier.verifyClaims(CONTEXT, DRAFT, MODEL));

        assertEquals(0, model.calls());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void alreadyInterruptedFactClassifierDoesNotInvokeProvider() {
        FailingModel model = new FailingModel(new IllegalStateException("synthetic forbidden provider call"));
        FactStatusClassifier classifier = classifier(model);
        Thread.currentThread().interrupt();

        assertThrows(CancellationException.class, () -> classifier.classify(QUESTION, CONTEXT, DRAFT, MODEL));

        assertEquals(0, model.calls());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptionAtSuccessfulFactResponseStopsDownstreamJudges() {
        InterruptingSuccessModel model = new InterruptingSuccessModel("CONSISTENT");
        FactFixture fixture = factFixture(model);

        assertThrows(CancellationException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertEquals(1, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptionAtSuccessfulExtractionStopsClaimJudgment() {
        InterruptingSuccessModel model = new InterruptingSuccessModel(
                "[\"The synthetic fixture status is amber.\"]");
        ClaimVerifierService verifier = claimVerifier(model);

        assertThrows(CancellationException.class, () -> verifier.verifyClaims(CONTEXT, DRAFT, MODEL));

        assertEquals(1, model.calls(), "successful extraction with cancellation must not start claim judgment");
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptionAtSuccessfulClassificationDoesNotReturnPass() {
        InterruptingSuccessModel model = new InterruptingSuccessModel("PASS");
        FactStatusClassifier classifier = classifier(model);

        assertThrows(CancellationException.class, () -> classifier.classify(QUESTION, CONTEXT, DRAFT, MODEL));

        assertEquals(1, model.calls());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static Stream<FailureCase> cancellations() {
        return Stream.of(
                new FailureCase("direct cancellation", () -> new CancellationException("synthetic cancellation"), false),
                new FailureCase("wrapped cancellation", () -> new RuntimeException("synthetic wrapper",
                        new CancellationException("synthetic cancellation")), false),
                new FailureCase("wrapped interruption", () -> new RuntimeException("synthetic wrapper",
                        new InterruptedException("synthetic interruption")), true));
    }

    private static Stream<FailureCase> ordinaryFailures() {
        return Stream.of(
                new FailureCase("provider unavailable", () -> new IllegalStateException("synthetic provider unavailable"), false),
                new FailureCase("provider timeout", () -> new RuntimeException("synthetic wrapper",
                        new TimeoutException("synthetic provider timeout")), false));
    }

    private static LlmResponseTerminalException terminal() {
        return new LlmResponseTerminalException("responses_incomplete", LlmFailureClass.TIMEOUT_SOFT,
                "synthetic auxiliary-only text", ChatResponseMetadata.builder()
                .id("synthetic-aux-response-id").modelName(MODEL)
                .tokenUsage(new TokenUsage(11, 7)).finishReason(FinishReason.LENGTH).build(),
                "incomplete", "max_output_tokens", "synthetic-provider-code");
    }

    private static void assertSanitizedTerminal(LlmResponseTerminalException terminal) {
        ChatResponseDto publicResponse = ChatResponseDto.terminal(terminal, 1L);
        assertEquals("", publicResponse.getContent(), "auxiliary output is not a user answer");
        assertNull(publicResponse.getModelUsed());
        var termination = publicResponse.getGenerationTermination();
        assertEquals("auxiliary_verification_terminated", termination.reason());
        assertEquals("failed", termination.status());
        assertFalse(termination.partial());
        assertFalse(termination.hasText());
        assertNull(termination.responseId());
        assertNull(termination.inputTokens());
        assertNull(termination.outputTokens());
        assertNull(termination.totalTokens());
        assertNull(termination.finishReason());
        assertNull(termination.incompleteReason());
        assertNull(termination.providerCode());
        assertNull(terminal.getCause());
        assertEquals(0, terminal.getSuppressed().length);
    }

    @Test
    void cancellationInsideAuxiliaryTerminalWinsBeforePublicFailure() {
        LlmResponseTerminalException terminal = terminal();
        terminal.initCause(new CancellationException("synthetic cancellation"));
        FailingModel model = new FailingModel(terminal);
        FactFixture fixture = factFixture(model);

        assertThrows(CancellationException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertEquals(1, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
    }

    @ParameterizedTest(name = "cancel cause hides auxiliary terminal interrupted={0}")
    @ValueSource(booleans = {false, true})
    void auxiliaryTerminalInsideCancellationCannotEscapeThroughCause(boolean interrupted) {
        Throwable cancellation = interrupted
                ? new InterruptedException("synthetic interruption")
                : new CancellationException("synthetic cancellation");
        cancellation.initCause(terminal());
        FailingModel model = new FailingModel(new RuntimeException("synthetic wrapper", cancellation));
        FactFixture fixture = factFixture(model);

        var thrown = assertThrows(CancellationException.class, () -> fixture.verifier().verifyDetailed(
                QUESTION, CONTEXT, "", DRAFT, MODEL, false));

        assertNull(LlmResponseTerminalException.find(thrown), "public terminal lookup must not recover judge output");
        assertNull(thrown.getCause());
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
        assertEquals(1, model.calls());
        verifyNoInteractions(fixture.classifier(), fixture.claims());
    }

    private static ClaimVerifierService claimVerifier(ChatModel model) {
        return new ClaimVerifierService(model, null, null, null);
    }

    private static FactStatusClassifier classifier(ChatModel model) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("judgeChatModel", model);
        return new FactStatusClassifier(factory.getBeanProvider(ChatModel.class));
    }

    private static FactFixture factFixture(ChatModel model) {
        FactStatusClassifier classifier = mock(FactStatusClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(FactVerificationStatus.PASS);
        ClaimVerifierService claims = mock(ClaimVerifierService.class);
        when(claims.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(DRAFT, List.of(), true, true));
        SourceAnalyzerService sources = mock(SourceAnalyzerService.class);
        when(sources.analyze(anyString(), anyString())).thenReturn(SourceCredibility.OFFICIAL);
        EvidenceGate gate = mock(EvidenceGate.class);
        when(gate.hasSufficientCoverage(anyString(), any(), any(), any(), anyBoolean())).thenReturn(true);
        PromptBuilder prompts = mock(PromptBuilder.class);
        when(prompts.build(any())).thenReturn("synthetic verifier prompt");
        return new FactFixture(new FactVerifierService(model, classifier, sources, claims, gate, prompts),
                classifier, claims);
    }

    private record FactFixture(FactVerifierService verifier, FactStatusClassifier classifier, ClaimVerifierService claims) {
    }

    private record FailureCase(String label, Supplier<RuntimeException> failure, boolean interruptExpected) {
        @Override public String toString() { return label; }
    }

    private static final class InterruptingSuccessModel implements ChatModel {
        private final String responseText;
        private final AtomicInteger calls = new AtomicInteger();

        private InterruptingSuccessModel(String responseText) {
            this.responseText = responseText;
        }

        int calls() { return calls.get(); }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from(responseText)).build();
            Thread.currentThread().interrupt();
            return response;
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return chat(List.of(messages));
        }
    }

    private static final class FailingModel implements ChatModel {
        private final RuntimeException failure;
        private final AtomicInteger calls = new AtomicInteger();

        private FailingModel(RuntimeException failure) {
            this.failure = failure;
        }

        int calls() { return calls.get(); }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            throw failure;
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return chat(List.of(messages));
        }
    }
}
