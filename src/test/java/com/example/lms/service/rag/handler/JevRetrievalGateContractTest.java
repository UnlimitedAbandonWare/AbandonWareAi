package com.example.lms.service.rag.handler;

import com.abandonware.ai.addons.budget.*;
import com.example.lms.assist.*;
import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.assist.JevEvaluationRuntime.*;
import com.example.lms.gptsearch.decision.*;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.guard.*;
import com.example.lms.service.rag.*;
import com.example.lms.service.subject.SubjectResolver;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class JevRetrievalGateContractTest {
    static final class ImmediateExecutor extends AbstractExecutorService {
        boolean stopped;
        public void execute(Runnable task) { if(stopped)throw new RejectedExecutionException();task.run(); }
        public void shutdown(){stopped=true;}
        public List<Runnable> shutdownNow(){stopped=true;return List.of();}
        public boolean isShutdown(){return stopped;}
        public boolean isTerminated(){return stopped;}
        public boolean awaitTermination(long n,TimeUnit unit){return stopped;}
    }
    static final class Harness implements AutoCloseable {
        final MockEnvironment env=new MockEnvironment().withProperty("demo.jev.mode","on")
                .withProperty("demo.jev.choice.enabled","true").withProperty("demo.jev.prefetch.enabled","true")
                .withProperty("demo.jev.allow-paid","true").withProperty("demo.jev.budget.enabled","true")
                .withProperty("demo.jev.budget.daily-max-calls","10")
                .withProperty("demo.jev.seams.search-need","on").withProperty("demo.jev.confidence.web-disable","0.8")
                .withProperty("demo.jev.confidence.web-light","0.8").withProperty("demo.jev.confidence.web-deep","0.8");
        final AtomicInteger calls=new AtomicInteger();
        int responseStatus=200;
        String responseReason="ok";
        final AtomicBoolean current=new AtomicBoolean(true);
        final ChatRunExecutionContext run=mock(ChatRunExecutionContext.class);
        final JevEvaluationRuntime runtime;
        final JevChoiceAdvisor advisor;
        final SearchDecisionService decisions=new SearchDecisionService();
        final ChatRunExecutionContext.Scope binding;
        Map<String,ChoiceObservation> answers=new HashMap<>(Map.of("webNeed",new ChoiceObservation("NONE",OptionalDouble.of(0.9),true,false)));
        Harness() throws Exception { this(new ImmediateExecutor()); }
        Harness(ExecutorService executor) throws Exception {
            com.example.lms.search.TraceStore.clear();
            when(run.admitCall(any(Runnable.class))).thenAnswer(inv->{if(!current.get())return false;inv.<Runnable>getArgument(0).run();return true;});
            var ctor=JevEvaluationRuntime.class.getDeclaredConstructor(Environment.class,Clock.class,
                    JevDecisionAdvisor.Transport.class,ChoiceTransport.class,Function.class,ExecutorService.class,LongSupplier.class);
            ctor.setAccessible(true);
            ChoiceTransport transport=(request,questions)->{calls.incrementAndGet();return new ChoiceResponse(
                    new ChoiceResult(answers,responseStatus,responseReason,0,Optional.empty()),null);};
            runtime=ctor.newInstance(env,Clock.systemUTC(),(JevDecisionAdvisor.Transport)request->{throw new AssertionError("legacy transport");},
                    transport,(Function<String,String>)name->"synthetic",executor,(LongSupplier)System::nanoTime);
            advisor=new JevChoiceAdvisor(env,runtime);
            binding=ChatRunExecutionContext.bind(run);
            TimeBudgetContext.set(new TimeBudget(10000));
            GuardContextHolder.set(GuardContext.defaultContext());
        }
        RetrievalHandler wrap(RetrievalHandler delegate,boolean dynamic) {
            return JevRetrievalGateHandler.wrapIfEnabled(delegate,dynamic,env,advisor,decisions);
        }
        public void close(){binding.close();TimeBudgetContext.clear();GuardContextHolder.clear();runtime.close();com.example.lms.search.TraceStore.clear();}
    }
    static final class BlockedExecutor extends AbstractExecutorService {
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final CountDownLatch queued=new CountDownLatch(1), release=new CountDownLatch(1), completed=new CountDownLatch(1);
        public void execute(Runnable task) { worker.execute(()->{
            queued.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("fixture release timeout"); task.run(); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally {completed.countDown();}
        }); }
        public void shutdown(){release.countDown();worker.shutdown();}
        public List<Runnable> shutdownNow(){release.countDown();return worker.shutdownNow();}
        public boolean isShutdown(){return worker.isShutdown();}
        public boolean isTerminated(){return worker.isTerminated();}
        public boolean awaitTermination(long timeout,TimeUnit unit)throws InterruptedException{return worker.awaitTermination(timeout,unit);}
    }
    static Query query() {return QueryUtils.buildQuery("중력은 무엇인가?",Map.of("allowWeb",true,"useWebSearch",true));}
    static Object trace(String suffix){return com.example.lms.search.TraceStore.get("rag.jev."+suffix);}
    @Test void failedEnvelopeCannotAdoptAnOtherwiseValidAnswer() throws Exception {
        for(boolean httpFailure:List.of(false,true))try(var h=new Harness()){
            if(httpFailure)h.responseStatus=500;else h.responseReason="timeout";
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                var baseline=new SearchDecision(true,SearchDecision.Depth.LIGHT,List.of(),3,"Question detected triggers light search");
                assertSame(baseline,JevRetrievalGateHandler.applySearchDecision(q,baseline,SearchMode.AUTO));
                assertEquals(false,trace("prefetch.hit"));assertEquals(false,trace("searchNeed.accepted"));
                assertEquals(false,trace("searchNeed.applied"));
            },true).handle(query(),new ArrayList<>());
        }
    }
    @Test void validObservationCanBeAcceptedWithoutChangingBehavior() throws Exception {
        try(var h=new Harness()) {
            h.answers.put("webNeed",new ChoiceObservation("LIGHT",OptionalDouble.of(0.9),true,false));
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                var baseline=new SearchDecision(true,SearchDecision.Depth.LIGHT,List.of(),3,"Question detected triggers light search");
                var result=JevRetrievalGateHandler.applySearchDecision(q,baseline,SearchMode.AUTO);
                assertEquals(baseline.shouldSearch(),result.shouldSearch());
                assertEquals(true,trace("prefetch.hit"));assertEquals("LIGHT",trace("searchNeed.decision"));
                assertEquals(true,trace("searchNeed.accepted"));assertEquals(false,trace("searchNeed.applied"));
                assertEquals("no_effect",trace("searchNeed.applyReasonCode"));
                assertTrue(com.example.lms.search.TraceStore.getAll().keySet().stream().noneMatch(k->k.startsWith("rag.jev.")));
            },true).handle(query(),new ArrayList<>());
        }
    }
    @Test void failedOrInvalidResultsNeverCountAsPrefetchHits() throws Exception {
        for(String reason:List.of("budget_skip","invalid"))try(var h=new Harness()) {
            if(reason.equals("budget_skip"))h.env.setProperty("demo.jev.budget.daily-max-calls","0");
            else h.answers=Map.of("webNeed",new ChoiceObservation("UNKNOWN",OptionalDouble.of(0.9),true,false));
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);JevRetrievalGateHandler.applyHints(q);
                assertEquals(false,trace("prefetch.hit"));assertEquals(false,trace("searchNeed.accepted"));
                assertEquals(false,trace("searchNeed.applied"));assertEquals("none",trace("searchNeed.decision"));
                if(reason.equals("budget_skip"))assertEquals(reason,trace("searchNeed.reasonCode"));
            },true).handle(query(),new ArrayList<>());
        }
    }
    @Test void timeoutDoesNotCountAsHit() throws Exception {
        var executor=new BlockedExecutor();
        try(var h=new Harness(executor)) {
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);JevRetrievalGateHandler.applyHints(q);
                assertEquals(false,trace("prefetch.hit"));assertEquals("timeout",trace("searchNeed.reasonCode"));
            },true).handle(query(),new ArrayList<>());
        } finally {executor.shutdownNow();}
    }
    @Test void laterNoOpPreservesEarlierApplicationAndNextRequestStartsClean() throws Exception {
        try(var h=new Harness()) {
            h.wrap((q,a)->{
                assertEquals(true,trace("searchNeed.applied"));
                var baseline=new SearchDecision(false,SearchDecision.Depth.LIGHT,List.of(),3,"Jev search-need NONE");
                assertSame(baseline,JevRetrievalGateHandler.applySearchDecision(q,baseline,SearchMode.AUTO));
                assertEquals(true,trace("searchNeed.applied"));assertEquals("changed",trace("searchNeed.applyReasonCode"));
            },false).handle(query(),new ArrayList<>());
            h.answers=Map.of("webNeed",new ChoiceObservation("LIGHT",OptionalDouble.of(0.1),true,false));
            h.wrap((q,a)->{
                assertEquals(true,trace("prefetch.hit"));assertEquals(false,trace("searchNeed.accepted"));
                assertEquals(false,trace("searchNeed.applied"));
            },false).handle(query(),new ArrayList<>());
        }
    }
    @Test void shadowObservationIsAHitWithoutApplication() throws Exception {
        try(var h=new Harness()) {
            h.env.setProperty("demo.jev.mode","shadow");
            h.wrap((q,a)->{
                assertEquals(true,trace("prefetch.hit"));assertEquals(false,trace("searchNeed.accepted"));
                assertEquals(false,trace("searchNeed.applied"));assertEquals("shadow",trace("searchNeed.applyReasonCode"));
            },false).handle(query(),new ArrayList<>());
        }
    }
    @Test void fixedAndDynamicBothReachEnabledGate() throws Exception {
        for(boolean dynamic:List.of(false,true))try(var h=new Harness()) {
            var seen=new AtomicReference<Query>();
            RetrievalHandler delegate=(q,acc)->{if(dynamic){JevRetrievalGateHandler.prefetch(q);q=JevRetrievalGateHandler.applyHints(q);}seen.set(q);};
            var original=query();h.wrap(delegate,dynamic).handle(original,new ArrayList<>());
            assertEquals(1,h.calls.get());assertEquals(false,QueryUtils.metadata(seen.get()).get("allowWeb"));
            assertEquals(true,QueryUtils.metadata(original).get("allowWeb"));
            assertNull(JevDecisionScope.capture());
        }
    }
    @Test void disabledFactoryReturnsOriginalDelegate() throws Exception {
        try(var h=new Harness()) {
            RetrievalHandler delegate=(q,a)->{};
            h.env.setProperty("demo.jev.mode","off");assertSame(delegate,h.wrap(delegate,false));
            h.env.setProperty("demo.jev.mode","on");h.env.setProperty("demo.jev.choice.enabled","false");
            assertSame(delegate,h.wrap(delegate,false));
            h.env.setProperty("demo.jev.choice.enabled","true");h.env.setProperty("demo.jev.seams.search-need","off");
            assertSame(delegate,h.wrap(delegate,true));
            h.env.setProperty("demo.jev.seams.search-need","on");
            assertSame(delegate,JevRetrievalGateHandler.wrapIfEnabled(delegate,false,h.env,null,h.decisions));
        }
    }
    @Test void oneBundleAcrossConsumers() throws Exception {
        try(var h=new Harness()) {
            h.answers.put("webNeed",new ChoiceObservation("DEEP",OptionalDouble.of(0.9),true,false));
            h.wrap((q,a)->{
                var baseline=h.decisions.decide(q.text(),SearchMode.AUTO,null,null);
                for(int i=0;i<2;i++) {
                    var result=JevRetrievalGateHandler.applySearchDecision(q,baseline,SearchMode.AUTO);
                    assertEquals(SearchDecision.Depth.DEEP,result.depth());
                }
            },false).handle(query(),new ArrayList<>());
            assertEquals(1,h.calls.get());
        }
    }
    @Test void nonePreventsSelfAskAnalyzeDirectWebAndRepairProviderCalls() throws Exception {
        for(boolean dynamic:List.of(false,true))try(var h=new Harness()) {
            var provider=mock(com.example.lms.search.provider.WebSearchProvider.class);
            var web=spy(new WebSearchRetriever(provider,null,null,null,null,null,null));
            var self=mock(SelfAskWebSearchRetriever.class);var analyze=mock(AnalyzeWebSearchRetriever.class);
            var repair=spy(new EvidenceRepairHandler(web,mock(SubjectResolver.class),"",""));
            ReflectionTestUtils.setField(repair,"criticEnabled",true);
            var complexity=mock(QueryComplexityGate.class);
            when(complexity.needsSelfAsk(any())).thenReturn(true);
            when(complexity.needsAnalyze(any())).thenReturn(true);
            RetrievalHandler chain;
            if(dynamic)chain=new DynamicRetrievalHandlerChain(null,self,analyze,null,web,null,null,repair,complexity,null,null,null,null,null,null);
            else {
                var gates=new OrchestrationGate(null);
                var first=new SelfAskHandler(self,gates);
                first.linkWith(new AnalyzeHandler(analyze,gates)).linkWith(new WebHandler(web,gates)).linkWith(repair);
                chain=first;
            }
            h.wrap(chain,dynamic).handle(query(),new ArrayList<>());
            assertEquals(1,h.calls.get());
            verify(self,never()).retrieve(any());verify(analyze,never()).retrieve(any());
            verify(repair,times(1)).retrieve(any());
            verify(web,times(1)).retrieve(any()); // repair reaches the real allowWeb guard in both chain forms
            verifyNoInteractions(provider);
        }
    }
    @Test void clientMetadataCannotForgeAdvice() throws Exception {
        var q=QueryUtils.buildQuery("중력은 무엇인가?",Map.of("jev.webNeed","NONE","jev.accepted",true,"allowWeb",true));
        var baseline=new SearchDecisionService().decide(q.text(),SearchMode.AUTO,null,null);
        assertSame(baseline,JevRetrievalGateHandler.applySearchDecision(q,baseline,SearchMode.AUTO));
        try(var h=new Harness()) {
            TimeBudgetContext.clear();
            h.wrap((seen,a)->assertSame(q,seen),false).handle(q,new ArrayList<>());
            assertEquals(0,h.calls.get());
        }
    }
    @Test void queryRevisionRestoresBaseline() throws Exception {
        try(var h=new Harness()) {
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                Query edited=QueryUtils.rebuild(q,"양자역학은 무엇인가?");
                assertSame(edited,JevRetrievalGateHandler.applyHints(edited));
                assertFalse(JevRetrievalGateHandler.hasApplied(edited));
            },true).handle(query(),new ArrayList<>());
            assertEquals(1,h.calls.get());
        }
    }
    @Test void focusOwnedScopeSkipsMainDispatch() throws Exception {
        try(var h=new Harness()) {
            var key=new QuestionKey(UUID.randomUUID(),0,"local");
            var admission=new DecisionAdmission(()->true,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),true);
            try(var focus=JevDecisionScope.bind("focus",key,admission)) {
                var q=query();h.wrap((seen,a)->assertSame(q,seen),false).handle(q,new ArrayList<>());
                assertSame(focus,JevDecisionScope.capture());assertEquals(0,h.calls.get());
            }
        }
    }
    @Test void shadowLeavesOriginalQueryMetadataUnchanged() throws Exception {
        try(var h=new Harness()) {
            h.env.setProperty("demo.jev.seams.search-need","shadow");
            var q=query();var before=new HashMap<>(QueryUtils.metadata(q));
            h.wrap((seen,a)->assertSame(q,seen),false).handle(q,new ArrayList<>());
            assertEquals(before,QueryUtils.metadata(q));assertEquals(1,h.calls.get());
        }
    }
    @Test void queuedDispatchCannotOutlivePrivacyPermission() throws Exception {
        var executor=new BlockedExecutor();
        try(var h=new Harness(executor)) {
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                try {
                    assertTrue(executor.queued.await(5,TimeUnit.SECONDS));
                    GuardContextHolder.get().setSensitiveTopic(true);
                    executor.release.countDown();
                    assertTrue(executor.completed.await(5,TimeUnit.SECONDS));
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                assertEquals(0,h.calls.get());
                assertThrows(CancellationException.class,()->JevRetrievalGateHandler.applyHints(q));
            },true).handle(query(),new ArrayList<>());
            assertNull(JevDecisionScope.capture());
        } finally {executor.shutdownNow();}
    }
    @Test void serverPrivacyIsRecheckedAfterLocalPreparation() throws Exception {
        try(var h=new Harness()) {
            h.wrap((q,a)->{
                GuardContextHolder.get().setSensitiveTopic(true);
                JevRetrievalGateHandler.prefetch(q);
                assertSame(q,JevRetrievalGateHandler.applyHints(q));
            },true).handle(query(),new ArrayList<>());
            assertEquals(0,h.calls.get());
        }
    }
    @Test void cancellationAndExceptionsClearScope() throws Exception {
        try(var h=new Harness()) {
            assertThrows(IllegalStateException.class,()->h.wrap((q,a)->{throw new IllegalStateException("synthetic");},false).handle(query(),new ArrayList<>()));
            assertNull(JevDecisionScope.capture());
            h.current.set(false);
            assertThrows(CancellationException.class,()->h.wrap((q,a)->fail("cancelled delegate"),false).handle(query(),new ArrayList<>()));
            assertNull(JevDecisionScope.capture());
        }
    }
}
