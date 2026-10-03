package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.*;
import static com.example.lms.llm.JudgeCallObservationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class StageBoundaryJudgeObservationTest {
    @AfterEach void cleanup(){TraceStore.clear();}
    @Test void actualPredispatchReceiptOverridesGenericFailureReason(){
        fact(null);TraceStore.put("factStatusClassifier.judge.disabledReason","judge_call_failed");
        var row=StageBoundaryBreadcrumbs.recordFromCurrentTrace("final").stream().filter(r->r.stage().equals("verification")).findFirst().orElseThrow();
        assertEquals(false,row.data().get("judgeCallAttempted"));assertEquals(0,row.data().get("judgeHttpAttemptCount"));assertEquals("judge_receipt",row.data().get("observationSource"));
    }
    @Test void validJudgeReceiptProducesSuccessfulObservedBoundary(){
        fact(model(()->response("PASS","judge-J")));
        var row=StageBoundaryBreadcrumbs.recordFromCurrentTrace("final").stream().filter(r->r.stage().equals("verification")).findFirst().orElseThrow();
        assertEquals(true,row.data().get("verificationOutcomeKnown"));assertEquals(true,row.data().get("judgeCallAttempted"));
        assertFalse(row.data().containsKey("judgeHttpAttemptCount"));
    }

    @Test void temporalFailureRemainsUnknownAfterSuccessfulJudgeCalls() {
        var temporal = org.mockito.Mockito.mock(com.example.lms.service.verification.TemporalConsistencyVerifier.class);
        org.mockito.Mockito.when(temporal.verify(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(java.time.LocalDate.class)))
                .thenThrow(new IllegalStateException("synthetic private failure"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var judge = model(() -> response(calls.incrementAndGet() == 1 ? "[\"A factual claim.\"]" : "[true]", "judge-J"));
        var service = new com.example.lms.service.verification.ClaimVerifierService(judge, null, null, temporal);
        assertFalse(service.verifyClaims("A factual claim.", "A factual claim.", "fixture").outcomeKnown());
        assertEquals(true, receipt("claim_extraction").get("outcomeKnown"));
        assertEquals(true, receipt("claim_judgment").get("outcomeKnown"));
        var row = StageBoundaryBreadcrumbs.recordFromCurrentTrace("final").stream()
                .filter(r -> r.stage().equals("verification")).findFirst().orElseThrow();
        assertEquals(false, row.data().get("verificationOutcomeKnown"));
        assertEquals("fail_soft", row.data().get("status"));
        assertEquals("temporal_verification_failed", row.data().get("reasonCode"));
        assertEquals(true, row.data().get("judgeInvocationStarted"));
        assertFalse(row.data().toString().contains("synthetic private failure"));
    }
}
