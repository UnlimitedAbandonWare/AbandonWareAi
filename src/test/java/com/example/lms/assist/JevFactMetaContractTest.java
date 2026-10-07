package com.example.lms.assist;

import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic control-flow evidence only; no provider quality or calibration claim. */
class JevFactMetaContractTest {
    @org.junit.jupiter.api.AfterEach void clear() {
        com.example.lms.search.TraceStore.clear();com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
    }
    static final String QUESTION = "What is the documented status?";
    static final String CONTEXT = "The official document states that the service is available.";
    static MockEnvironment environment() {
        return JevCandidateSignalContractTest.environment()
                .withProperty("demo.jev.fact-meta.enabled", "true")
                .withProperty("demo.jev.fact-meta.external-consent", "true")
                .withProperty("demo.jev.fact-meta.baseline-reserve-ms", "200")
                .withProperty("demo.jev.fact-meta.probability-threshold.consistent", "0.9")
                .withProperty("demo.jev.fact-meta.probability-threshold.mismatch", "0.95")
                .withProperty("demo.jev.fact-meta.probability-threshold.insufficient", "0.9");
    }
    static QuestionKey key(String question) {
        return new QuestionKey(UUID.randomUUID(), 3, com.example.lms.trace.SafeRedactor.hashValue(question));
    }
    static ChoiceResponse response(String label, double probability) {
        return new ChoiceResponse(new ChoiceResult(Map.of("factMeta",
                new ChoiceObservation(label, OptionalDouble.of(probability), true, false)),
                200, "ok", 0, Optional.empty()), null);
    }
    @SuppressWarnings("unchecked")
    static Optional<String> evaluate(JevChoiceAdvisor advisor, String question, String context, long remainingMs) {
        var method = assertDoesNotThrow(() -> JevChoiceAdvisor.class.getMethod(
                "factMetaVerdict", String.class, String.class, long.class));
        try {
            return (Optional<String>) method.invoke(advisor, question, context, remainingMs);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(wrapped.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @ParameterizedTest @ValueSource(strings={"CONSISTENT", "MISMATCH", "INSUFFICIENT"})
    void admittedLabelUsesOneMetaCallAndKeepsWholeState(String label) throws Exception {
        try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (request, questions)-> {
            assertEquals(List.of("factMeta"), questions.stream().map(ChoiceQuestion::id).toList());
            return response(label, .99);
        }); var scope=JevDecisionScope.bind("main", key(QUESTION), JevCandidateSignalContractTest.admission())) {
            assertEquals(Optional.of(label), evaluate(f.advisor, QUESTION, CONTEXT, 2000));
            assertEquals(1, f.calls.get());
            assertTrue(f.sent.get().question().contains(QUESTION));
            assertTrue(f.sent.get().question().contains(CONTEXT));
            assertTrue(scope.result().isEmpty(), "meta must not overwrite search observations");
            assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 2000).isEmpty());
            assertEquals(1, f.calls.get(), "same purpose has one attempt, not a result cache");
        }
    }
    @ParameterizedTest @ValueSource(strings={"enabled", "external-consent", "baseline-reserve-ms", "probability-threshold.mismatch"})
    void missingDedicatedPolicyNeverCallsTransport(String field) throws Exception {
        var env=environment();env.setProperty("demo.jev.fact-meta."+field, "");
        try(var f=new JevCandidateSignalContractTest.Fixture(env, (r,q)->response("CONSISTENT", .99));
            var scope=JevDecisionScope.bind("main", key(QUESTION), JevCandidateSignalContractTest.admission())) {
            assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 2000).isEmpty());
            assertEquals(0, f.calls.get());
        }
    }
    @Test void absentScopeIsBaselineEvenWithAllFlagsOn() throws Exception {
        try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (r,q)->response("CONSISTENT", .99))) {
            assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 2000).isEmpty());assertEquals(0,f.calls.get());
        }
    }
    @Test void deniedPrivacyOrWrongQuestionRevisionCannotBorrowScope() throws Exception {
        for(boolean privacy : List.of(false, true)) {
            var admission=new DecisionAdmission(()->true, System.nanoTime()+TimeUnit.SECONDS.toNanos(3), privacy);
            try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (r,q)->response("CONSISTENT", .99));
                var scope=JevDecisionScope.bind("main", key(privacy?"old question":QUESTION), admission)) {
                assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 2000).isEmpty());assertEquals(0,f.calls.get());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"huge", "redacted", "malformed"})
    void incompleteOrAlteredStateCannotDispatch(String kind) throws Exception {
        String context=switch(kind) {case "huge"->"x".repeat(1300);case "redacted"->"synthetic@example.invalid";default->"broken\uD800";};
        try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (r,q)->response("CONSISTENT", .99));
            var scope=JevDecisionScope.bind("main", key(QUESTION), JevCandidateSignalContractTest.admission())) {
            assertTrue(evaluate(f.advisor, QUESTION, context, 2000).isEmpty());assertEquals(0,f.calls.get());
        }
    }
    @Test void reserveAndShadowDoNotAddProductCalls() throws Exception {
        for(String mode : List.of("on", "shadow", "off")) {
            var env=environment().withProperty("demo.jev.mode", mode);
            try(var f=new JevCandidateSignalContractTest.Fixture(env, (r,q)->response("CONSISTENT", .99));
                var scope=JevDecisionScope.bind("main", key(QUESTION), JevCandidateSignalContractTest.admission())) {
                assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 150).isEmpty());
                if(!"on".equals(mode))assertTrue(evaluate(f.advisor, QUESTION, CONTEXT, 2000).isEmpty());
                assertEquals(0,f.calls.get());
            }
        }
    }
    @Test void lowProbabilityAndTransportFailureReturnBaseline() throws Exception {
        for(String kind : List.of("low", "failure", "429", "invalid", "unknown")) {
            try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (r,q)-> {
                if("failure".equals(kind))throw new IllegalStateException("synthetic outage");
                if("429".equals(kind))return new ChoiceResponse(new ChoiceResult(Map.of(),429,"rate_limited",0,Optional.empty()),1L);
                if("invalid".equals(kind))return new ChoiceResponse(new ChoiceResult(Map.of("factMeta",
                        new ChoiceObservation("CONSISTENT",OptionalDouble.empty(),false,false)),200,"ok",0,Optional.empty()),null);
                return response("unknown".equals(kind)?"OTHER":"CONSISTENT",.5);
            }); var scope=JevDecisionScope.bind("main", key(QUESTION), JevCandidateSignalContractTest.admission())) {
                assertTrue(evaluate(f.advisor,QUESTION,CONTEXT,2000).isEmpty());assertEquals(1,f.calls.get());
            }
        }
    }
    @Test void currentnessLossAfterTransportPropagatesCancellation() throws Exception {
        var current=new AtomicBoolean(true);
        var admission=new DecisionAdmission(current::get,System.nanoTime()+TimeUnit.SECONDS.toNanos(3),true);
        try(var f=new JevCandidateSignalContractTest.Fixture(environment(), (r,q)-> {
            current.set(false);return response("CONSISTENT",.99);
        }); var scope=JevDecisionScope.bind("main",key(QUESTION),admission)) {
            assertThrows(java.util.concurrent.CancellationException.class,()->evaluate(f.advisor,QUESTION,CONTEXT,2000));
            assertEquals(1,f.calls.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"extra", "duplicate", "missing", "wrong-model", "not-normalized", "wrong-winner", "scalar"})
    void strictMetaWireRejectsMalformedDistribution(String kind) throws Exception {
        try(var wire=new JevChoiceContractTest.Fixture()) {
            String answer="\"type\":\"choice\",\"choice\":\"CONSISTENT\",\"probabilities\":{\"CONSISTENT\":0.99,\"MISMATCH\":0.005,\"INSUFFICIENT\":0.005}";
            if("missing".equals(kind))answer=answer.replace(",\"INSUFFICIENT\":0.005","");
            if("not-normalized".equals(kind))answer=answer.replace("0.005","0.5");
            if("wrong-winner".equals(kind))answer=answer.replace("0.99","0.1").replace("0.005","0.45");
            if("scalar".equals(kind))answer="\"choice\":\"CONSISTENT\",\"probability\":0.99";
            String tail="extra".equals(kind)?",\"webNeed\":{\"choice\":\"NONE\"}":"duplicate".equals(kind)?",\"factMeta\":{"+answer+"}":"";
            wire.response="{\"model\":\""+("wrong-model".equals(kind)?"other":"jev")+"\",\"answers\":{\"factMeta\":{"+answer+"}"+tail+"}}";
            var field=assertDoesNotThrow(()->JevChoiceAdvisor.class.getField("FACT_META"));
            var question=(ChoiceQuestion)field.get(null);
            var result=wire.evaluate("QUESTION: synthetic CONTEXT: synthetic",List.of(question),8192).result();
            assertTrue(result.answers().isEmpty()||!result.answers().get("factMeta").schemaValid(),kind);
        }
    }
    @ParameterizedTest @ValueSource(strings={"accepted", "off", "failed", "low", "no-scope", "negative-claim", "parent-expired"})
    void fullVerifierPreservesBaselineAndDownstreamMemoryContract(String scenario) throws Exception {
        var env=environment();if("off".equals(scenario))env.setProperty("demo.jev.fact-meta.enabled","false");
        try(var f=new JevCandidateSignalContractTest.Fixture(env,(r,q)-> {
            if("failed".equals(scenario))throw new IllegalStateException("synthetic outage");
            return response("CONSISTENT","low".equals(scenario)?.5:.99);
        }); var scope="no-scope".equals(scenario)?null:JevDecisionScope.bind("main",key(QUESTION),
                "parent-expired".equals(scenario)?new DecisionAdmission(()->true,System.nanoTime()-1,true):JevCandidateSignalContractTest.admission())) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            dev.langchain4j.model.chat.ChatModel baseline=new dev.langchain4j.model.chat.ChatModel() {
                public dev.langchain4j.model.chat.response.ChatResponse chat(java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
                    calls.incrementAndGet();return dev.langchain4j.model.chat.response.ChatResponse.builder()
                            .aiMessage(dev.langchain4j.data.message.AiMessage.from("CONSISTENT")).build();
                }
            };
            var classifier=org.mockito.Mockito.mock(com.example.lms.service.verification.FactStatusClassifier.class);
            org.mockito.Mockito.when(classifier.classify(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(com.example.lms.service.verification.FactVerificationStatus.PASS);
            var source=org.mockito.Mockito.mock(com.example.lms.service.verification.SourceAnalyzerService.class);
            org.mockito.Mockito.when(source.analyze(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(com.example.lms.domain.enums.SourceCredibility.OFFICIAL);
            var claims=org.mockito.Mockito.mock(com.example.lms.service.verification.ClaimVerifierService.class);
            org.mockito.Mockito.when(claims.verifyClaims(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new com.example.lms.service.verification.ClaimVerifierService.VerificationResult("draft",List.of(),true,!"negative-claim".equals(scenario)));
            var gate=org.mockito.Mockito.mock(com.example.lms.service.rag.guard.EvidenceGate.class);
            org.mockito.Mockito.when(gate.hasSufficientCoverage(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
            var prompt=org.mockito.Mockito.mock(com.example.lms.prompt.PromptBuilder.class);
            org.mockito.Mockito.when(prompt.build(org.mockito.ArgumentMatchers.any())).thenReturn("synthetic prompt");
            var service=new com.example.lms.service.FactVerifierService(baseline,classifier,source,claims,gate,prompt);
            org.springframework.test.util.ReflectionTestUtils.setField(service,"jevChoiceAdvisor",f.advisor);
            com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(2000));
            if("parent-expired".equals(scenario)) {
                assertThrows(com.example.lms.llm.ModelSelectionException.class,()->service.verifyDetailed(QUESTION,CONTEXT.repeat(3),"","draft","synthetic",false));
                assertEquals(0,calls.get());assertEquals(0,f.calls.get());return;
            }
            var result=service.verifyDetailed(QUESTION,CONTEXT.repeat(3),"","draft","synthetic",false);
            boolean accepted=Set.of("accepted","negative-claim").contains(scenario);
            assertEquals(accepted?0:1,calls.get(),"accepted meta replaces rather than adds to baseline call");
            assertEquals(Set.of("off","no-scope").contains(scenario)?0:1,f.calls.get());
            assertEquals("negative-claim".equals(scenario)?"rejected":"pass",result.status());
            assertEquals(!"negative-claim".equals(scenario),result.acceptedForMemory());
            assertTrue(result.outcomeKnown());assertEquals("draft",result.answer());
        }
    }
    @ParameterizedTest @ValueSource(strings={"demo.jev.fact-meta.external-consent", "demo.jev.fact-meta.enabled", "demo.jev.choice.enabled", "demo.jev.prefetch.enabled", "demo.jev.mode"})
    void policyRevocationDuringFlightCannotApplyReturnedLabel(String property) throws Exception {
        var env=environment();
        try(var f=new JevCandidateSignalContractTest.Fixture(env,(r,q)-> {
            env.setProperty(property,property.endsWith("mode")?"off":"false");return response("MISMATCH",.99);
        });var scope=JevDecisionScope.bind("main",key(QUESTION),JevCandidateSignalContractTest.admission())) {
            assertTrue(evaluate(f.advisor,QUESTION,CONTEXT,2000).isEmpty());assertEquals(1,f.calls.get());
        }
    }
    @Test void nestedMetaDoesNotReplaceSearchHandleOrSearchObservation() throws Exception {
        try(var f=new JevCandidateSignalContractTest.Fixture(environment(),(r,q)-> {
            if(q.get(0).id().equals("factMeta"))return response("CONSISTENT",.99);
            return new ChoiceResponse(new ChoiceResult(Map.of("webNeed",new ChoiceObservation("NONE",OptionalDouble.of(.99),true,false)),200,"ok",0,Optional.empty()),null);
        })) {
            var key=key(QUESTION);var admission=JevCandidateSignalContractTest.admission();
            try(var scope=JevDecisionScope.bind("main",key,admission)) {
                var search=f.advisor.prefetch(key,"main",QUESTION,List.of(JevChoiceAdvisor.WEB_NEED),admission);
                assertEquals("NONE",f.advisor.await(search,key,admission.deadlineNanos()).answers().get("webNeed").choice());
                var observed=scope.result();
                try(var nested=JevDecisionScope.bind(scope)) {
                    assertEquals(Optional.of("CONSISTENT"),evaluate(f.advisor,QUESTION,CONTEXT,2000));
                }
                assertSame(search,scope.handle);assertEquals(observed,scope.result());
                assertEquals("ok",f.advisor.await(search,key,admission.deadlineNanos()).reasonCode());
                assertTrue(evaluate(f.advisor,QUESTION,CONTEXT+" revision",2000).isEmpty());
                assertEquals(2,f.calls.get());f.advisor.discard(search);
            }
        }
    }
}
