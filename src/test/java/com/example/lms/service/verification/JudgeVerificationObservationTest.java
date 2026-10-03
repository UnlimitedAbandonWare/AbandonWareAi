package com.example.lms.service.verification;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.lms.llm.JudgeCallObservationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class JudgeVerificationObservationTest {
    @AfterEach void cleanup(){TraceStore.clear();}
    @Test void extractionAndJudgmentHaveDistinctTypedReceipts(){
        AtomicInteger calls=new AtomicInteger();
        var service=new ClaimVerifierService(model(()->response(calls.incrementAndGet()==1?"[\"A factual claim.\"]":"[true]","judge-J")),null,null,null);
        assertTrue(service.verifyClaims("A factual claim.","A factual claim.","requested").outcomeKnown());
        assertEquals(2,calls.get());var extraction=receipt("claim_extraction");var judgment=receipt("claim_judgment");
        assertNotEquals(extraction.get("attemptId"),judgment.get("attemptId"));assertEquals(true,extraction.get("outcomeKnown"));assertEquals(true,judgment.get("outcomeKnown"));
    }
    @Test void malformedExtractionIsUnknownAndJudgmentNeverStarted(){
        var service=new ClaimVerifierService(model(()->response("not-json","judge-J")),null,null,null);
        assertFalse(service.verifyClaims("A factual claim.","A factual claim.","requested").outcomeKnown());
        assertEquals(false,receipt("claim_extraction").get("outcomeKnown"));assertEquals(false,receipt("claim_judgment").get("invocationStarted"));
        assertEquals(0,receipt("claim_judgment").get("httpAttemptCount"));
    }
}
