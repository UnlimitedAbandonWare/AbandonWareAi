package com.example.lms.assist;

import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JevPurposeIsolationTest {
    @Test void relevanceDoesNotReuseOrDiscardSearchHandleAndDigestMismatchCannotApply() throws Exception {
        try(var f=new JevCandidateSignalContractTest.Fixture(JevCandidateSignalContractTest.environment(),(request,questions)->{
            var answers=new LinkedHashMap<String,ChoiceObservation>();
            for(var question:questions)answers.put(question.id(),new ChoiceObservation(
                    question.id().startsWith("relevance")?"RELEVANT":"NONE",OptionalDouble.of(.99),true,false));
            return new ChoiceResponse(new ChoiceResult(answers,200,"ok",0,Optional.empty()),null);
        })) {
            var key=JevCandidateSignalContractTest.key();var admission=JevCandidateSignalContractTest.admission();
            try(var scope=JevDecisionScope.bind("main",key,admission)) {
                var search=f.advisor.prefetch(key,"main","synthetic",List.of(JevChoiceAdvisor.WEB_NEED),admission);
                var method=assertDoesNotThrow(()->JevChoiceAdvisor.class.getMethod("prefetchRelevance",
                        QuestionKey.class,String.class,String.class,String.class,int.class,DecisionAdmission.class));
                var relevance=(EvaluationHandle)method.invoke(f.advisor,key,"candidate-digest","main","synthetic candidates",2,admission);
                assertNotSame(search,relevance);assertSame(search,scope.handle);
                var keyMethod=JevChoiceAdvisor.class.getMethod("candidateKey",QuestionKey.class,String.class);
                var relevanceKey=(QuestionKey)keyMethod.invoke(null,key,"candidate-digest");
                var changed=(QuestionKey)keyMethod.invoke(null,key,"different-digest");
                assertNotEquals(key,relevanceKey);assertNotEquals(relevanceKey,changed);
                assertEquals("cancelled",f.advisor.await(relevance,changed,admission.deadlineNanos()).reasonCode());
                assertEquals("ok",f.advisor.await(search,key,admission.deadlineNanos()).reasonCode());
                f.advisor.discard(search);f.advisor.discard(relevance);
            }
        }
    }
}
