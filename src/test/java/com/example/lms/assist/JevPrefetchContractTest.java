package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.lms.assist.JevChoiceAdvisor.*;
import static com.example.lms.assist.JevEvaluationRuntime.*;

class JevPrefetchContractTest {
    static final class ChoiceLogs implements AutoCloseable {
        final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(JevDecisionAdvisor.class);
        final ch.qos.logback.classic.Level previous=logger.getLevel();
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();
        ChoiceLogs(){logger.setLevel(ch.qos.logback.classic.Level.INFO);appender.start();logger.addAppender(appender);}
        List<String> lines(){return appender.list.stream().map(e->e.getFormattedMessage()).filter(s->s.contains("[AWX][jev][choice]")).toList();}
        public void close(){logger.detachAppender(appender);appender.stop();logger.setLevel(previous);}
    }
    @Test void shadowCompletionLogsOnceWithoutAwaitAndPreservesMissingCost(){
        try(var h=new Harness();var logs=new ChoiceLogs()){
            h.env.setProperty("demo.jev.mode","shadow");
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);
                assertTrue(logs.lines().isEmpty());
                h.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(37));h.executor.runNext();
                assertEquals(1,logs.lines().size());
                var line=logs.lines().get(0);
                assertTrue(line.contains("phase=completion surface=main mode=shadow questions=1 reasonCode=ok latencyMs=37 httpStatus=200 billedUsd=none"),line);
                assertFalse(line.contains("Synthetic"));assertFalse(line.contains("synthetic"));assertFalse(line.contains("local-only"));
                h.choice.await(handle,key,a.deadlineNanos());h.choice.await(handle,key,a.deadlineNanos());
                assertEquals(1,logs.lines().size());
            }
        }
    }
    @Test void discardedLateCompletionKeepsObservedCostAndHasOneLog(){
        try(var h=new Harness();var logs=new ChoiceLogs()){
            h.response=new ChoiceResponse(new ChoiceResult(Map.of(),200,"ok",0,Optional.of(new java.math.BigDecimal("0.0123"))),null);
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);
                h.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
                assertEquals("timeout",h.choice.await(handle,key,a.deadlineNanos()).reasonCode());
                h.choice.discard(handle);h.executor.runNext();
                assertEquals(1,logs.lines().size());
                assertTrue(logs.lines().get(0).contains("latencyMs=200 httpStatus=200 billedUsd=0.0123"));
            }
        }
    }
    @Test void admissionRejectionIsDistinctAndOffIsSilent(){
        try(var h=new Harness();var logs=new ChoiceLogs()){
            h.env.setProperty("demo.jev.budget.daily-max-calls","0");
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                h.prefetch(key,a);h.prefetch(key,a);
                assertEquals(1,logs.lines().size());
                assertTrue(logs.lines().get(0).contains("phase=admission"));
                assertTrue(logs.lines().get(0).contains("dispatched=false"));
                assertTrue(logs.lines().get(0).contains("reasonCode=budget_skip"));
            }
            h.env.setProperty("demo.jev.mode","off");h.prefetch(h.key(),a);
            assertEquals(1,logs.lines().size());assertEquals(0,h.calls.get());
        }
    }
    static final List<ChoiceQuestion> QUESTIONS=List.of(WEB_NEED);
    static final class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks=new ArrayDeque<>();boolean reject;
        public void execute(Runnable task){if(reject)throw new RejectedExecutionException();tasks.add(task);}
        void runNext(){assertFalse(tasks.isEmpty());tasks.remove().run();}
        public void shutdown(){reject=true;}
        public List<Runnable> shutdownNow(){reject=true;var pending=new ArrayList<>(tasks);tasks.clear();return pending;}
        public boolean isShutdown(){return reject;}
        public boolean isTerminated(){return reject&&tasks.isEmpty();}
        public boolean awaitTermination(long n,TimeUnit u){return isTerminated();}
    }
    static final class Harness implements AutoCloseable {
        final MockEnvironment env=new MockEnvironment().withProperty("demo.jev.mode","on")
                .withProperty("demo.jev.allow-paid","true").withProperty("demo.jev.choice.enabled","true")
                .withProperty("demo.jev.prefetch.enabled","true").withProperty("demo.jev.confidence.default","0.70")
                .withProperty("demo.jev.confidence.web-light","0.60").withProperty("demo.jev.budget.enabled","true")
                .withProperty("demo.jev.budget.daily-max-calls","10");
        final QueueExecutor executor=new QueueExecutor();final AtomicLong now=new AtomicLong(1_000_000_000L);
        final AtomicInteger calls=new AtomicInteger();final JevEvaluationRuntime runtime;
        final JevDecisionAdvisor legacy;final JevChoiceAdvisor choice;
        ChoiceResponse response=new ChoiceResponse(new ChoiceResult(Map.of("webNeed",
                new ChoiceObservation("LIGHT",OptionalDouble.of(0.9),true,false)),200,"ok",0,Optional.empty()),null);
        Harness(){
            runtime=new JevEvaluationRuntime(env,Clock.systemUTC(),req->{calls.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.WEB,null,null);},
                    (req,qs)->{calls.incrementAndGet();return response;},name->"synthetic",executor,now::get);
            legacy=new JevDecisionAdvisor(env,runtime);choice=new JevChoiceAdvisor(env,runtime);
        }
        QuestionKey key(){return new QuestionKey(UUID.randomUUID(),0,"local-only");}
        DecisionAdmission admission(){return new DecisionAdmission(()->true,now.get()+TimeUnit.SECONDS.toNanos(3),true);}
        EvaluationHandle prefetch(QuestionKey key,DecisionAdmission admission){return choice.prefetch(key,"main","Synthetic question",QUESTIONS,admission);}
        public void close(){legacy.close();runtime.close();}
    }
    @Test void discardedDispatchStillConsumesDailyQuota() {
        try(var h=new Harness()){
            h.env.withProperty("demo.jev.budget.daily-max-calls","1");
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){var handle=h.prefetch(key,a);h.choice.discard(handle);h.executor.runNext();assertTrue(h.choice.await(handle,key,a.deadlineNanos()).answers().isEmpty());}
            var next=h.key();
            try(var scope=JevDecisionScope.bind("main",next,a)){assertEquals("budget_skip",h.choice.await(h.prefetch(next,a),next,a.deadlineNanos()).reasonCode());}
            assertEquals(1,h.calls.get());
        }
    }
    @Test void lateAuthResponseLatchesBeforePermitRelease() {
        try(var h=new Harness()){
            h.response=new ChoiceResponse(new ChoiceResult(Map.of(),401,"auth_invalid",0,Optional.empty()),null);
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(151));
                assertEquals("timeout",h.choice.await(handle,key,a.deadlineNanos()).reasonCode());
                h.choice.discard(handle);h.executor.runNext();
                assertEquals("auth_blocked",h.legacy.advise("cue","synthetic","CUE").reasonCode());
                assertEquals(1,h.calls.get());
            }
        }
    }
    @Test void thirdConcurrentCallIsBusyAcrossLegacyAndChoice() {
        try(var h=new Harness()){
            h.env.withProperty("demo.jev.mode","shadow");
            assertEquals("shadow",h.legacy.advise("cue","synthetic","CUE").reasonCode());
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                h.prefetch(key,a);
                assertEquals("busy",h.legacy.advise("cue","synthetic","CUE").reasonCode());
                assertEquals(2,h.executor.tasks.size());
                h.executor.runNext();h.executor.runNext();assertEquals(2,h.calls.get());
            }
        }
    }
    @Test void editedQuestionNeverAppliesOldVerdict() {
        try(var h=new Harness()){
            var key=h.key();var a=h.admission();var edited=new QuestionKey(key.localRequestNonce(),1,"changed-local");
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.executor.runNext();
                assertTrue(h.choice.await(handle,edited,a.deadlineNanos()).answers().isEmpty());
                assertTrue(handle.staleDiscarded());
                var second=h.prefetch(edited,a);
                assertTrue(h.choice.await(second,edited,a.deadlineNanos()).answers().isEmpty());
                assertEquals(1,h.calls.get());
            }
        }
    }
    @Test void waitDeadlineIsNotRenewedAtSecondConsumer() {
        try(var h=new Harness()){
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(151));
                assertEquals("timeout",h.choice.await(handle,key,a.deadlineNanos()).reasonCode());
                h.executor.runNext();
                assertEquals("timeout",h.choice.await(handle,key,a.deadlineNanos()).reasonCode());
            }
        }
    }
    @Test void parentTimeoutDoesNotStartFallbackGeneration() {
        try(var h=new Harness()){
            var key=h.key();var a=new DecisionAdmission(()->true,h.now.get()+TimeUnit.MILLISECONDS.toNanos(30),true);
            var fallback=new AtomicInteger();
            try(var scope=JevDecisionScope.bind("main",key,a)){
                var handle=h.prefetch(key,a);h.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(31));
                var error=assertThrows(com.example.lms.llm.ModelSelectionException.class,()->{
                    h.choice.await(handle,key,a.deadlineNanos());fallback.incrementAndGet();
                });
                assertEquals("backend_timeout",error.code());assertEquals(0,fallback.get());
            }
        }
    }
    @Test void noSecondDispatchInNestedFocusRequest() {
        try(var h=new Harness()){
            var key=h.key();var a=h.admission();
            try(var focus=JevDecisionScope.bind("focus",key,a)){
                var first=h.choice.prefetch(key,"focus","synthetic",QUESTIONS,a);
                try(var main=JevDecisionScope.bind("main",key,a)){
                    assertSame(first,h.prefetch(key,a));
                    assertEquals(1,h.executor.tasks.size());h.executor.runNext();assertEquals(1,h.calls.get());
                }
            }
        }
    }
    @Test void executorRejectionRefundsExactlyOnce() {
        try(var h=new Harness()){
            h.env.withProperty("demo.jev.budget.daily-max-calls","1");h.executor.reject=true;
            var key=h.key();var a=h.admission();
            try(var scope=JevDecisionScope.bind("main",key,a)){assertEquals("busy",h.choice.await(h.prefetch(key,a),key,a.deadlineNanos()).reasonCode());}
            h.executor.reject=false;var next=h.key();
            try(var scope=JevDecisionScope.bind("main",next,a)){var handle=h.prefetch(next,a);h.executor.runNext();assertEquals("LIGHT",h.choice.await(handle,next,a.deadlineNanos()).answers().get("webNeed").choice());}
            var third=h.key();
            try(var scope=JevDecisionScope.bind("main",third,a)){assertEquals("budget_skip",h.choice.await(h.prefetch(third,a),third,a.deadlineNanos()).reasonCode());}
            assertEquals(1,h.calls.get());
        }
    }
}
