package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.SourceCredibility;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactVerifierUnavailableOutcomeTest {
    @BeforeEach @AfterEach void clear() { Thread.interrupted(); TraceStore.clear(); TimeBudgetContext.clear(); }

    @Test void actualJudgeTimeoutIsUnavailableWithoutAcceptingDraftOrMemory() {
        var result = verify(service(new FakeModel(null, true), FactVerificationStatus.PASS, true));
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        assertTrue(unavailable(result));
    }

    @Test void malformedNonemptyVerdictIsNotInfrastructureFailure() {
        var result = verify(service(new FakeModel("not-a-meta-verdict", false), FactVerificationStatus.PASS, true));
        assertEquals("unknown", result.status());
        assertFalse(result.acceptedForMemory());
        assertFalse(unavailable(result));
    }

    @Test void knownInsufficientClassifierCannotBecomeUnavailableThroughUnknownClaim() {
        var result = verify(service(new FakeModel(null, true), FactVerificationStatus.INSUFFICIENT, false));
        assertFalse(result.acceptedForMemory());
        assertFalse(unavailable(result));
    }

    @Test void staleJudgeDisabledReasonsDoNotContaminateNewMalformedOutcome() {
        TraceStore.put("claimVerifier.judge.disabledReason", "judge_call_failed");
        TraceStore.put("factStatusClassifier.judge.disabledReason", "judge_model_unavailable");
        var result = verify(service(new FakeModel("not-a-meta-verdict", false), FactVerificationStatus.PASS, true));
        assertEquals("unknown", result.status());
        assertFalse(unavailable(result));
        assertNull(TraceStore.get("claimVerifier.judge.disabledReason"));
        assertNull(TraceStore.get("factStatusClassifier.judge.disabledReason"));
    }

    @Test void legacyUnknownConstructorDoesNotGrantUnavailablePermission() {
        var result = new FactVerifierService.DetailedVerificationResult("draft", "unknown", false, false);
        assertFalse(unavailable(result));
    }

    @Test void missingModelOutputAloneIsNotInfrastructurePermission() {
        ChatModel blank = mock(ChatModel.class);
        var result = verify(service(blank, FactVerificationStatus.PASS, true));
        assertEquals("unknown", result.status());
        assertFalse(unavailable(result));
        assertFalse(result.acceptedForMemory());
    }

    private static FactVerifierService.DetailedVerificationResult verify(FactVerifierService service) {
        return service.verifyDetailed("question", "supporting official context ".repeat(8), "", "draft", "model", false);
    }

    private static FactVerifierService service(ChatModel model, FactVerificationStatus status, boolean accepted) {
        SourceAnalyzerService source = mock(SourceAnalyzerService.class);
        when(source.analyze(anyString(), anyString())).thenReturn(SourceCredibility.OFFICIAL);
        FactStatusClassifier classifier = mock(FactStatusClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyString(), anyString())).thenReturn(status);
        ClaimVerifierService claim = mock(ClaimVerifierService.class);
        when(claim.verifyClaims(anyString(), anyString(), anyString())).thenReturn(
                new ClaimVerifierService.VerificationResult("draft", List.of(), accepted, accepted));
        EvidenceGate gate = mock(EvidenceGate.class);
        when(gate.hasSufficientCoverage(anyString(), any(), any(), any(), anyBoolean())).thenReturn(true);
        PromptBuilder prompt = mock(PromptBuilder.class);
        when(prompt.build(any())).thenReturn("synthetic verifier prompt");
        return new FactVerifierService(model, classifier, source, claim, gate, prompt);
    }

    private record FakeModel(String text, boolean timeout) implements ChatModel {
        @Override public ChatResponse chat(ChatMessage... messages) { return response(); }
        @Override public ChatResponse chat(List<ChatMessage> messages) { return response(); }
        private ChatResponse response() {
            if (timeout) throw new RuntimeException(new TimeoutException("synthetic timeout"));
            return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
        }
    }

    // The pre-patch record has no such accessor; default false is the real baseline contract.
    private static boolean unavailable(FactVerifierService.DetailedVerificationResult result) {
        try { return (boolean) result.getClass().getMethod("verificationUnavailable").invoke(result); }
        catch (NoSuchMethodException beforePatch) { return false; }
        catch (InvocationTargetException failure) { throw new AssertionError(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
