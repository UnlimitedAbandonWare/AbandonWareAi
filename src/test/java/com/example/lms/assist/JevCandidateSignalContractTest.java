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
                new ChoiceObservation(i==1?"RELEVANT":i==2?"UNCERTAIN":"IRRELEVANT",
                        OptionalDouble.of(.99),true,false));
        return new ChoiceResponse(new ChoiceResult(answers,200,"ok",0,Optional.empty()),null);
    }
    static List<Content> candidates() {
        List<Content> rows = new ArrayList<>();
        for (int i=0;i<5;i++) rows.add(Content.from(TextSegment.from(
                (i==0?"UNIQUE_COUNTEREVIDENCE_":"FULL_EVIDENCE_")+i+"_"+"x".repeat(180)+"_FULL_BODY_END",
                Metadata.from(Map.of("sourceId","source-"+i,"citationNumber",i+1,
                        "url","https://example.com/"+i,"title","Source "+i,"confidence",.9,"lineStart",1,"protected",i==0?"true":"false")))));
        return rows;
    }
    static QuestionKey key() { return new QuestionKey(UUID.randomUUID(),1,"question-fixture"); }
    static DecisionAdmission admission() {return new DecisionAdmission(()->true,
            System.nanoTime()+TimeUnit.SECONDS.toNanos(3),true);}
    @SuppressWarnings("unchecked")
    static List<Content> rerank(Fixture f,List<Content> input,QuestionKey key,DecisionAdmission admission) throws Exception {
        var method=f.signal.getClass().getMethod("rerank",String.class,List.class,QuestionKey.class,DecisionAdmission.class);
        return (List<Content>)method.invoke(f.signal,"synthetic question",input,key,admission);
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
            assertFalse(f.sent.get().question().contains("_FULL_BODY_END"));
            assertTrue(f.sent.get().question().contains("candidate_relevance"));
            assertTrue(f.sent.get().question().length()<=1200);
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
