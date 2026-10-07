package com.example.lms.assist;

import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class JevCandidateSignalContractTest {
    @AfterEach void clear() { com.example.lms.search.TraceStore.clear(); }
    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("demo.jev.mode", "on")
                .withProperty("demo.jev.allow-paid", "true")
                .withProperty("demo.jev.choice.enabled", "true").withProperty("demo.jev.prefetch.enabled", "true")
                .withProperty("demo.jev.candidate-signal.enabled", "true")
                .withProperty("demo.jev.candidate-signal.external-consent", "true")
                .withProperty("demo.jev.confidence.default", "0.5")
                .withProperty("demo.jev.decision-wait-ms", "100");
    }
    static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JevDecisionAdvisor.EvalRequest> sent = new AtomicReference<>();
        final JevEvaluationRuntime runtime;
        final JevChoiceAdvisor advisor;
        final Object signal;
        Fixture(MockEnvironment env, BiFunction<JevDecisionAdvisor.EvalRequest,List<ChoiceQuestion>,ChoiceResponse> response) throws Exception {
            runtime = new JevEvaluationRuntime(env, Clock.systemUTC(), request -> {
                fail("candidate path must use the existing choice transport"); return null;
            }, (request, questions) -> {
                calls.incrementAndGet(); sent.set(request); return response.apply(request, questions);
            }, name -> "fixture", null, System::nanoTime);
            advisor = new JevChoiceAdvisor(env, runtime);
            Class<?> type = assertDoesNotThrow(() -> Class.forName("com.example.lms.assist.JevCandidateSignal"));
            signal = type.getConstructor(Environment.class, JevChoiceAdvisor.class).newInstance(env, advisor);
        }
        public void close() { runtime.close(); }
    }
    static ChoiceResponse ordered(JevDecisionAdvisor.EvalRequest request, List<ChoiceQuestion> questions) {
        Map<String,ChoiceObservation> answers = new LinkedHashMap<>();
        for (int i=0; i<questions.size(); i++) answers.put(questions.get(i).id(),
                new ChoiceObservation(i==1||i==2?"RELEVANT":"IRRELEVANT",
                        OptionalDouble.of(.99),true,false));
        return new ChoiceResponse(new ChoiceResult(answers,200,"ok",0,Optional.empty()),null);
    }
    static List<Content> candidates() {
        List<Content> rows = new ArrayList<>();
        for (int i=0;i<5;i++) rows.add(Content.from(TextSegment.from(
                (i==0?"UNIQUE_COUNTEREVIDENCE_":"FULL_EVIDENCE_")+i+"_"+"x".repeat(i==4?180:20)+"_FULL_BODY_END",
                Metadata.from(Map.of("sourceId","source-"+i,"citationNumber",i+1,
                        "url","https://example.com/"+i,"title","Source "+i,"confidence",.9,"lineStart",1,"protected",i==0?"true":"false")))));
        return rows;
    }
    static QuestionKey key() { return new QuestionKey(UUID.randomUUID(),1,"question-fixture"); }
    static DecisionAdmission admission() {return new DecisionAdmission(()->true,
            System.nanoTime()+TimeUnit.SECONDS.toNanos(3),true);}
    static List<Content> shortCandidates() {
        return List.of(Content.from("Unrelated fixture."),Content.from("Synthetic answer evidence."),
                Content.from("Synthetic counterevidence."),Content.from("Other unrelated fixture."),
                Content.from("Protected tail " + "x".repeat(200)));
    }
    @SuppressWarnings("unchecked")
    static List<Content> rerank(Fixture f,List<Content> input,QuestionKey key,DecisionAdmission admission) throws Exception {
        var method=f.signal.getClass().getMethod("rerank",String.class,List.class,QuestionKey.class,DecisionAdmission.class);
        try(var scope=JevDecisionScope.current()==null?JevDecisionScope.bind("main",key,admission):null) {
            return (List<Content>)method.invoke(f.signal,"synthetic question",input,key,admission);
        }
    }
    @Test void uncertainEvenAtHighProbabilityKeepsEntireBaseline() throws Exception {
        try(Fixture f=new Fixture(environment(),(request,questions)->{
            var answers=new LinkedHashMap<>(ordered(request,questions).result().answers());
            answers.put(questions.get(2).id(),new ChoiceObservation("UNCERTAIN",OptionalDouble.of(.99),true,false));
            return new ChoiceResponse(new ChoiceResult(answers,200,"ok",0,Optional.empty()),null);
        })) {
            var baseline=shortCandidates();var question=key();var admitted=admission();
            try(var scope=JevDecisionScope.bind("main",question,admitted)) {
                var result=rerank(f,baseline,question,admitted);
                assertEquals(1,f.calls.get());
                for(int i=0;i<baseline.size();i++)assertSame(baseline.get(i),result.get(i));
                assertEquals("unaccepted_signal",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
            }
        }
    }
    @Test void twoFusionsInSameScopeDispatchAtMostOneCandidateBatch() throws Exception {
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var question=key();var admitted=admission();
            try(var scope=JevDecisionScope.bind("main",question,admitted)) {
                rerank(f,shortCandidates(),question,admitted);
                var second=shortCandidates();
                var result=rerank(f,second,question,admitted);
                assertEquals(1,f.calls.get());
                for(int i=0;i<second.size();i++)assertSame(second.get(i),result.get(i));
            }
        }
    }
    @Test void evidenceBeyondExcerptLimitSkipsInsteadOfJudgingPrefix() throws Exception {
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var question=key();var admitted=admission();var baseline=new ArrayList<>(candidates());
            baseline.set(0,Content.from("x".repeat(160)+" NOT safe above 10 mg; fresh revision B."));
            try(var scope=JevDecisionScope.bind("main",question,admitted)) {
                assertSame(baseline,rerank(f,baseline,question,admitted));
                assertEquals(0,f.calls.get());
            }
        }
    }
    @Test void runtimeSelectionWithoutVerifiedPackingContextNeverDispatches() throws Exception {
        var run=org.mockito.Mockito.mock(com.example.lms.service.chat.ChatRunExecutionContext.class);
        org.mockito.Mockito.when(run.admitCall(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(3000));
        com.example.lms.service.guard.GuardContextHolder.set(new com.example.lms.service.guard.GuardContext());
        try(var binding=com.example.lms.service.chat.ChatRunExecutionContext.bind(run);
                Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var method=f.signal.getClass().getMethod("rerank",String.class,List.class);
            for(var baseline:List.of(shortCandidates(),shortCandidates().subList(0,2),
                    List.of(shortCandidates().get(1),shortCandidates().get(1)))) {
                assertSame(baseline,method.invoke(f.signal,"synthetic question",baseline));
            }
            assertEquals(0,f.calls.get());
            assertEquals("selection_context_missing",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            com.example.lms.service.guard.GuardContextHolder.clear();
        }
    }
    @Test void permissiveAdmissionCannotReplaceBoundOwnerAdmission() throws Exception {
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var question=key();var baseline=shortCandidates();
            var denied=new DecisionAdmission(()->false,System.nanoTime()-1,false);
            try(var scope=JevDecisionScope.bind("main",question,denied)) {
                var result=rerank(f,baseline,question,admission());
                assertEquals(0,f.calls.get());
                for(int i=0;i<baseline.size();i++)assertSame(baseline.get(i),result.get(i));
            }
        }
    }
    @Test void closingCapturedOwnerDiscardsLateCandidateResult() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try(Fixture f=new Fixture(environment().withProperty("demo.jev.decision-wait-ms","2000"),(request,questions)->{
            entered.countDown();
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return ordered(request,questions);
        })) {
            var question=key();var admitted=admission();var baseline=shortCandidates();
            try(var owner=JevDecisionScope.bind("main",question,admitted)) {
                var captured=JevDecisionScope.capture();
                Future<List<Content>> pending=worker.submit(()->{
                    try(var binding=JevDecisionScope.bind(captured)) {
                        return rerank(f,baseline,question,admitted);
                    }
                });
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                owner.close();release.countDown();
                var result=pending.get(2,TimeUnit.SECONDS);
                for(int i=0;i<baseline.size();i++)assertSame(baseline.get(i),result.get(i));
                assertEquals(1,f.calls.get());
            }
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void freshQuestionScopeDoesNotInheritCandidateAttempt() throws Exception {
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            for(int revision=1;revision<=2;revision++) {
                var question=new QuestionKey(UUID.randomUUID(),revision,"question-"+revision);
                var admitted=admission();
                try(var scope=JevDecisionScope.bind("main",question,admitted)) {
                    rerank(f,shortCandidates(),question,admitted);
                    try(var rebound=JevDecisionScope.bind(JevDecisionScope.capture())) {
                        assertEquals(shortCandidates(),rerank(f,shortCandidates(),question,admitted));
                    }
                }
            }
            assertEquals(2,f.calls.get());
        }
    }
    @Test void oversizedQueryHasZeroDispatch() throws Exception {
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var question=key();var admitted=admission();var baseline=shortCandidates();
            try(var scope=JevDecisionScope.bind("main",question,admitted)) {
                var method=f.signal.getClass().getMethod("rerank",String.class,List.class,QuestionKey.class,DecisionAdmission.class);
                assertSame(baseline,method.invoke(f.signal,"x".repeat(256)+" NOT the previous question",baseline,question,admitted));
                assertEquals(0,f.calls.get());
            }
        }
    }
    @Test void skippedAdvisoryPreservesComposedPromptAndSourceSpans() throws Exception {
        var baseline=new ArrayList<>(candidates());
        String protectedClaim="For revision B, anchor bonus is NOT 12.5%; limit 10 mg only.";
        baseline.set(0,Content.from(TextSegment.from("x".repeat(160)+" "+protectedClaim,
                baseline.get(0).textSegment().metadata())));
        var props=new ai.abandonware.nova.config.NovaOrchestrationProperties();
        props.getRagCompressor().setMaxContents(5);
        props.getRagCompressor().setMaxCharsPerContent(500);
        props.getRagCompressor().setTargetChars(2000);
        props.getRagCompressor().setAblationPressureThreshold(0.0d);
        var compressor=new ai.abandonware.nova.orch.compress.DynamicContextCompressor(props);
        var builder=new com.example.lms.prompt.StandardPromptBuilder();
        var before=compressor.composeForPrompt("anchor revision B",List.of(),baseline);
        String baselinePrompt=builder.build(List.of(com.example.lms.prompt.PromptContext.builder().rag(before.rag()).build()),"anchor revision B");
        try(Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var selected=rerank(f,baseline,key(),admission());
            var after=compressor.composeForPrompt("anchor revision B",List.of(),selected);
            String candidatePrompt=builder.build(List.of(com.example.lms.prompt.PromptContext.builder().rag(after.rag()).build()),"anchor revision B");
            assertEquals(baselinePrompt,candidatePrompt);
            assertTrue(candidatePrompt.contains(protectedClaim));
            assertTrue(candidatePrompt.contains("FULL_EVIDENCE_4"));
            assertEquals(before.rag().stream().map(row->row.textSegment().metadata().toMap()).toList(),
                    after.rag().stream().map(row->row.textSegment().metadata().toMap()).toList());
            assertEquals(0,f.calls.get());
        }
    }
    @Test void defaultOffAndMissingConsentHaveZeroDispatch() throws Exception {
        for (MockEnvironment env : List.of(new MockEnvironment(),
                environment().withProperty("demo.jev.candidate-signal.external-consent","false"),
                environment().withProperty("demo.jev.mode","off"))) {
            try (Fixture f=new Fixture(env,JevCandidateSignalContractTest::ordered)) {
                var baseline=candidates();
                assertEquals(baseline,rerank(f,baseline,key(),admission()));
                assertEquals(0,f.calls.get());
            }
        }
    }
    @Test void acceptedSignalsOnlyPermuteOriginalObjectsAndPreserveProtectedTail() throws Exception {
        try (Fixture f=new Fixture(environment(),JevCandidateSignalContractTest::ordered)) {
            var baseline=candidates(); var result=rerank(f,baseline,key(),admission());
            assertEquals(List.of(baseline.get(1),baseline.get(2),baseline.get(0),baseline.get(3),baseline.get(4)),result);
            assertEquals(1,f.calls.get());
            for(Content original:baseline) assertTrue(result.stream().anyMatch(row->row==original));
            assertEquals("source-0",result.get(2).textSegment().metadata().getString("sourceId"));
            assertTrue(result.get(2).textSegment().text().endsWith("_FULL_BODY_END"));
            assertTrue(f.sent.get().question().contains("_FULL_BODY_END"));
            assertFalse(f.sent.get().question().contains("FULL_EVIDENCE_4"));
            assertTrue(f.sent.get().question().contains("candidate_relevance"));
            assertTrue(f.sent.get().question().length()<=1200);
        }
    }
    static Fixture loopback(JevChoiceContractTest.Fixture wire)throws Exception {
        MockEnvironment env=environment().withProperty("demo.jev.decision-wait-ms","3000")
                .withProperty("demo.jev.request-timeout-ms","2000")
                .withProperty("demo.jev.endpoint","http://127.0.0.1:"+wire.server.getAddress().getPort()+"/v1/evaluate");
        JevGatewayClient client=new JevGatewayClient(name->"synthetic");
        return new Fixture(env,client::evaluateChoices);
    }
    @Test void loopbackForeignResponseIdKeepsOriginalObjectsInOrder()throws Exception {
        assertInvalidLoopbackBatch(JevChoiceContractTest.CANDIDATE_FOREIGN);
    }
    @Test void loopbackRawDuplicateResponseIdKeepsOriginalObjectsInOrder()throws Exception {
        assertInvalidLoopbackBatch(JevChoiceContractTest.CANDIDATE_DUPLICATE);
    }
    @Test void loopbackMissingResponseIdKeepsOriginalObjectsInOrder()throws Exception {
        assertInvalidLoopbackBatch(JevChoiceContractTest.CANDIDATE_MISSING);
    }
    static void assertInvalidLoopbackBatch(String rawResponse)throws Exception {
        try(var wire=new JevChoiceContractTest.Fixture();var f=loopback(wire)) {
            wire.response=rawResponse;
            var baseline=new ArrayList<>(candidates().subList(0,2));
            var result=rerank(f,baseline,key(),admission());
            assertEquals("invalid_response",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
            assertEquals(baseline.size(),result.size());
            for(int i=0;i<baseline.size();i++)assertSame(baseline.get(i),result.get(i));
            assertEquals(1,wire.calls.get());
        }
    }
    @Test void loopbackAcceptedSignalsStablyReorderOnlyFourOriginalObjects()throws Exception {
        try(var wire=new JevChoiceContractTest.Fixture();var f=loopback(wire)) {
            wire.response=JevChoiceContractTest.CANDIDATE_GOOD.substring(0,JevChoiceContractTest.CANDIDATE_GOOD.length()-2)
                    +",\"relevance2\":{\"choice\":\"RELEVANT\",\"probability\":0.99},\"relevance3\":{\"choice\":\"IRRELEVANT\",\"probability\":0.99}}}";
            var baseline=candidates();
            var bodies=baseline.stream().map(row->row.textSegment().text()).toList();
            var metadata=baseline.stream().map(row->new LinkedHashMap<>(row.textSegment().metadata().toMap())).toList();
            var result=rerank(f,baseline,key(),admission());
            assertEquals("applied",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
            assertEquals(baseline.size(),result.size());
            int[] order={1,2,0,3,4};
            for(int i=0;i<order.length;i++)assertSame(baseline.get(order[i]),result.get(i));
            assertEquals(bodies,baseline.stream().map(row->row.textSegment().text()).toList());
            assertEquals(metadata,baseline.stream().map(row->row.textSegment().metadata().toMap()).toList());
            assertEquals(4,JevChoiceContractTest.JSON.readTree(wire.body.get()).path("questions").size());
            assertEquals(1,wire.calls.get());
        }
    }
    @Test void malformedMissingAndLowConfidenceResultsKeepBaseline() throws Exception {
        for(String reason:List.of("upstream_error","missing","low_confidence")) {
            try(Fixture f=new Fixture(environment(),(request,questions)-> {
                var result=ordered(request,questions);
                if("upstream_error".equals(reason)) return new ChoiceResponse(
                        new ChoiceResult(Map.of(),500,reason,0,Optional.empty()),null);
                var answers=new LinkedHashMap<>(result.result().answers());
                if("missing".equals(reason))answers.remove(questions.get(0).id());
                else answers.replaceAll((id,observation)->new ChoiceObservation(observation.choice(),OptionalDouble.of(.1),true,false));
                return new ChoiceResponse(new ChoiceResult(answers,200,"ok",0,Optional.empty()),null);
            })) {
                var baseline=candidates();assertEquals(baseline,rerank(f,baseline,key(),admission()));
            }
        }
    }
    @Test void timeoutKeepsOriginalOrder() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try(Fixture f=new Fixture(environment().withProperty("demo.jev.decision-wait-ms","20"),(request,questions)->{
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return ordered(request,questions);
        })) {
            var baseline=candidates();assertEquals(baseline,rerank(f,baseline,key(),admission()));
            assertEquals("timeout",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
        } finally {release.countDown();}
    }
    @Test void changedQuestionOrCancelledRunDiscardsLateResult() throws Exception {
        AtomicBoolean current=new AtomicBoolean(true);
        try(Fixture f=new Fixture(environment(),(request,questions)->{
            current.set(false);return ordered(request,questions);
        })) {
            var baseline=candidates();
            assertEquals(baseline,rerank(f,baseline,key(),new DecisionAdmission(current::get,
                    System.nanoTime()+TimeUnit.SECONDS.toNanos(3),true)));
            assertEquals("cancelled",com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
        }
    }

    @Test void cancellationDuringPostAwaitDigestKeepsOriginalOrder() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicBoolean evaluationReturned = new AtomicBoolean();
        List<Content> baseline = new ArrayList<>(candidates()) {
            @Override public Iterator<Content> iterator() {
                Iterator<Content> delegate = super.iterator();
                return new Iterator<>() {
                    public boolean hasNext() { return delegate.hasNext(); }
                    public Content next() {
                        if (evaluationReturned.get()) current.set(false);
                        return delegate.next();
                    }
                };
            }
        };
        try (Fixture f = new Fixture(environment(), (request, questions) -> {
            evaluationReturned.set(true);
            return ordered(request, questions);
        })) {
            assertEquals(baseline, rerank(f, baseline, key(), new DecisionAdmission(current::get,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3), true)));
            assertEquals(1, f.calls.get());
            assertFalse(current.get(), "synthetic cancellation occurs in the post-await digest");
            assertEquals("cancelled", com.example.lms.search.TraceStore.get("rag.jev.candidate.reasonCode"));
        }
    }
}
