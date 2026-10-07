package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.auth.AuthorityScorer;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCredibilityReleaseRegressionTest {
    private static final String QUESTION = "What status does the synthetic fixture report?";
    private static final String DRAFT = "The fixture status is amber.";
    // Only host types come from the incident. Text and paths are synthetic.
    private static final String MIXED = """
            https://www.korean.go.kr/fixture-alpha The fixture status is amber.
            https://www.reddit.com/r/fixture-alpha The fixture status is amber.
            https://namu.wiki/w/fixture-alpha The fixture status is amber.
            https://namu.wiki/w/fixture-beta The fixture status is amber.
            """;

    @AfterEach
    void clearContext() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void realDefaultAuthorityWeightsAreUsedByTheFixture() {
        AuthorityScorer scorer = scorer();
        assertEquals(1.0, scorer.weightFor("https://www.korean.go.kr/fixture-alpha"));
        assertEquals(0.55, scorer.weightFor("https://www.reddit.com/r/fixture-alpha"));
        assertEquals(0.25, scorer.weightFor("https://namu.wiki/w/fixture-alpha"));
    }

    @Test
    void mixedAuthorityWithoutContradictoryClaimsStaysUnknown() {
        assertEquals(SourceCredibility.UNKNOWN, analyzer().analyze(QUESTION, MIXED));
    }

    @Test
    void unanimousOfficialAuthorityStillClassifiesAsOfficial() {
        assertEquals(SourceCredibility.OFFICIAL, analyzer().analyze(QUESTION,
                "https://www.korean.go.kr/fixture-alpha The fixture status is amber."));
    }

    @Test
    void unknownWeightedCommunityIsNotInventedSpeculation() {
        assertEquals(SourceCredibility.UNKNOWN, analyzer().analyze(QUESTION,
                "https://www.reddit.com/r/fixture-alpha The fixture status is amber."));
    }

    @Test
    void explicitContradictionStillClassifiesAsConflicting() {
        assertEquals(SourceCredibility.CONFLICTING, analyzer().analyze(QUESTION,
                MIXED + "The sources contradict one another."));
    }

    @Test
    void explicitSpeculationStillClassifiesAsSpeculation() {
        assertEquals(SourceCredibility.FAN_MADE_SPECULATION, analyzer().analyze(QUESTION,
                "https://www.reddit.com/r/fixture-alpha This is unconfirmed speculation."));
    }

    @Test
    void unrelatedControversyIsNotAClaimContradiction() {
        assertEquals(SourceCredibility.UNKNOWN, analyzer().analyze(QUESTION,
                MIXED + "The fixture article also describes an unrelated historical 논란."));
    }

    @Test
    void officialSourceAndSpeculationCueDoNotEstablishContradiction() {
        assertEquals(SourceCredibility.FAN_MADE_SPECULATION, analyzer().analyze(QUESTION,
                MIXED + "A separate unconfirmed speculation remains unverified."));
    }

    @Test
    void historicalDisclaimerDoesNotRejectCurrentSupportedClaim() {
        ClaimVerifierService claims = claims(true);
        var result = verifier(claims).verifyDetailed(QUESTION, MIXED,
                "Earlier answer: unconfirmed speculation; requires independent confirmation.",
                DRAFT, "fixture", true);
        verify(claims).verifyClaims(anyString(), eq(DRAFT), eq("fixture"));
        assertEquals("pass", result.status());
        assertTrue(result.outcomeKnown());
    }

    @Test
    void speculativeSourceReachesClaimsButCannotEnterMemory() {
        ClaimVerifierService claims = claims(true);
        var result = verifier(claims).verifyDetailed(QUESTION,
                MIXED + "A separate unconfirmed speculation remains unverified.", "", DRAFT, "fixture", false);
        verify(claims).verifyClaims(anyString(), eq(DRAFT), eq("fixture"));
        assertEquals("insufficient", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        assertFalse(result.verificationUnavailable());
    }

    @Test
    void negativeClaimStillRejectsDespiteSpeculativeSource() {
        ClaimVerifierService claims = claims(false);
        var result = verifier(claims).verifyDetailed(QUESTION,
                MIXED + "A separate unconfirmed speculation remains unverified.", "", DRAFT, "fixture", false);
        verify(claims).verifyClaims(anyString(), eq(DRAFT), eq("fixture"));
        assertEquals("rejected", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void unavailableClassifierRemainsUnknownDespiteSpeculativeSourceAndPositiveClaims() {
        var result = verifier(claims(true), true).verifyDetailed(QUESTION,
                MIXED + "A separate unconfirmed speculation remains unverified.", "", DRAFT, "fixture", false);
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void mixedAuthorityReachesClaimVerificationInsteadOfAutomaticRejection() {
        ClaimVerifierService claims = claims(true);
        var result = verifier(claims).verifyDetailed(QUESTION, MIXED, "", DRAFT, "fixture", false);
        assertEquals("pass", result.status());
        assertTrue(result.outcomeKnown());
        assertTrue(result.acceptedForMemory());
        assertEquals(DRAFT, result.answer());
        verify(claims).verifyClaims(anyString(), eq(DRAFT), eq("fixture"));
        var release = ChatWorkflow.applyFinalVerificationReleaseGate(result.answer(), true,
                result.status(), result.outcomeKnown(), result.acceptedForMemory());
        assertTrue(release.releaseAllowed());
    }

    @Test
    void rejectedClaimsRemainRejectedForMixedAuthority() {
        ClaimVerifierService claims = claims(false);
        var result = verifier(claims).verifyDetailed(QUESTION, MIXED, "", DRAFT, "fixture", false);
        verify(claims).verifyClaims(anyString(), eq(DRAFT), eq("fixture"));
        assertEquals("rejected", result.status());
        assertFalse(result.acceptedForMemory());
        var release = ChatWorkflow.applyFinalVerificationReleaseGate(result.answer(), true,
                result.status(), result.outcomeKnown(), result.acceptedForMemory());
        assertFalse(release.releaseAllowed());
    }

    @Test
    void explicitConflictStillRejectsBeforeClaimVerification() {
        ClaimVerifierService claims = claims(true);
        var result = verifier(claims).verifyDetailed(QUESTION,
                MIXED + "The sources contradict one another.", "", DRAFT, "fixture", false);
        assertEquals("rejected", result.status());
        assertFalse(result.acceptedForMemory());
        verifyNoInteractions(claims);
    }

    private static AuthorityScorer scorer() {
        return new AuthorityScorer("", "", "", "", "", "", "", "", "",
                1.0, 0.85, 0.80, 0.70, 0.55, 0.25);
    }

    @SuppressWarnings("unchecked")
    private static SourceAnalyzerService analyzer() {
        ObjectProvider<AuthorityScorer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scorer());
        return new SourceAnalyzerService(provider);
    }

    private static ClaimVerifierService claims(boolean accepted) {
        ClaimVerifierService claims = mock(ClaimVerifierService.class);
        when(claims.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(DRAFT, List.of(), true, accepted));
        return claims;
    }

    private static FactVerifierService verifier(ClaimVerifierService claims) {
        return verifier(claims, false);
    }

    private static FactVerifierService verifier(ClaimVerifierService claims, boolean classifierUnavailable) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(AiMessage.from("CONSISTENT")).build();
            }
            @Override
            public ChatResponse chat(ChatMessage... messages) {
                return chat(List.of(messages));
            }
        };
        FactStatusClassifier classifier = mock(FactStatusClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (classifierUnavailable) {
                        TraceStore.put("factStatusClassifier.judge.disabledReason", "judge_call_failed");
                    }
                    return FactVerificationStatus.PASS;
                });
        EvidenceGate gate = mock(EvidenceGate.class);
        when(gate.hasSufficientCoverage(anyString(), any(), any(), any(), anyBoolean())).thenReturn(true);
        PromptBuilder builder = mock(PromptBuilder.class);
        when(builder.build(any())).thenReturn("synthetic verification prompt");
        return new FactVerifierService(model, classifier, analyzer(), claims, gate, builder);
    }
}
