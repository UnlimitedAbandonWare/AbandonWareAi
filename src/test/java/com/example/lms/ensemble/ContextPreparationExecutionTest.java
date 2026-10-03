package com.example.lms.ensemble;
import com.example.lms.llm.*;
import com.example.lms.search.TraceStore;
import com.example.lms.prompt.StandardPromptBuilder;
import com.abandonware.ai.addons.budget.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ContextPreparationExecutionTest {
    @AfterEach void clear(){TraceStore.clear();TimeBudgetContext.clear();}
    record Fixture(DiverseSamplingOrchestrator sampler,ModelRuntimeHealthTracker ledger,DynamicChatModelFactory factory,
            AtomicInteger calls,PreparedContextPacket original){}
    Fixture fixture(String outcome,long milliseconds)throws Exception{
        var ledger=new ModelRuntimeHealthTracker();String timeline=ledger.beginRequestTimeline("synthetic","session");
        TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,timeline);TimeBudgetContext.set(new TimeBudget(milliseconds));
        var original=(PreparedContextPacket)PreparedContextPacketTest.original();var calls=new AtomicInteger();
        ChatModel delegate=new ChatModel(){@Override public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest req){
            calls.incrementAndGet();
            if(outcome.equals("timeout")){
                try{Thread.sleep(10000);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new CancellationException("stopped");}
            }
            if(outcome.equals("429"))throw new IllegalStateException("synthetic 429");
            if(outcome.equals("cancel"))throw new CancellationException("synthetic cancellation");
            String json=outcome.equals("invalid")?"invalid":"{\"selectedSpanIds\":[\""+original.spans().get(0).spanId()+"\"],\"claims\":[],\"conflicts\":[],\"missingEvidence\":[]}";
            return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
        }};
        var model=ledger.decorateRequestAttempt(delegate,"context_prepare",ledger.redactedRequestAttemptRoute(
            "synthetic","qwen3.5:9b","http://127.0.0.1:1","test"),Map.of());
        var factory=mock(DynamicChatModelFactory.class);
        when(factory.contextPreparationReady(anyString())).thenReturn(true);when(factory.lcForContextPreparation(anyString(),anyInt())).thenReturn(model);
        var sampler=new DiverseSamplingOrchestrator(factory,mock(StochasticParamSampler.class),mock(com.example.lms.guard.FinalSigmoidGate.class),new StandardPromptBuilder());
        ReflectionTestUtils.setField(sampler,"contextAttemptLedger",ledger);
        return new Fixture(sampler,ledger,factory,calls,original);
    }
    @Test void normalAndInvalidOr429NeverMakeAnotherHelperCall()throws Exception{
        for(String outcome:List.of("ok","invalid","429")){
            clear();var f=fixture(outcome,30000);
            var prepared=f.sampler.prepareContext(f.original,()->{});
            assertEquals(outcome.equals("ok"),prepared.prepared());assertEquals(1,f.calls.get());
            assertFalse(f.sampler.prepareContext(f.original,()->{}).prepared());assertEquals(1,f.calls.get());
        }
    }
    @Test @Timeout(10) void timeoutDiscardsOnlyHelperAndPreservesPrimaryTime()throws Exception{
        var f=fixture("timeout",7500);
        // Start the request budget after Mockito/model fixture setup, as the production caller does.
        TimeBudgetContext.set(new TimeBudget(7500));
        assertSame(f.original,f.sampler.prepareContext(f.original,()->{}));
        assertEquals(1,f.calls.get());assertTrue(TimeBudgetContext.get().remainingMillis()>4000);
    }
    @Test void unavailableOrInsufficientBudgetMakesZeroCalls()throws Exception{
        for(boolean shortBudget:List.of(true,false)){
            clear();var f=fixture("ok",shortBudget?6000:30000);
            if(!shortBudget)when(f.factory.contextPreparationReady(anyString())).thenReturn(false);
            assertSame(f.original,f.sampler.prepareContext(f.original,()->{}));assertEquals(0,f.calls.get());
            verify(f.factory,never()).lcForContextPreparation(anyString(),anyInt());
        }
    }
    @Test void cancellationAndLateSourceInvalidationTerminate()throws Exception{
        var cancelled=fixture("cancel",30000);
        assertThrows(CancellationException.class,()->cancelled.sampler.prepareContext(cancelled.original,()->{}));
        clear();var late=fixture("ok",30000);
        assertThrows(CancellationException.class,()->late.sampler.prepareContext(late.original,()->{
            if(late.calls.get()>0)throw new CancellationException("source invalidated");
        }));
        assertEquals(1,late.calls.get());
    }
    @Test void sameOwnedRunReusesPacketAndChangedIdentityCannotReuse()throws Exception{
        var eviction=new ScheduledThreadPoolExecutor(1);
        try{
            var constructor=com.example.lms.service.chat.ChatRunRegistry.class.getDeclaredConstructor(ScheduledExecutorService.class);
            constructor.setAccessible(true);var registry=constructor.newInstance(eviction);
            ReflectionTestUtils.setField(registry,"replayCapacity",16);ReflectionTestUtils.setField(registry,"ttlSeconds",60);
            var run=registry.beginOrJoin(501L).context();var f=fixture("ok",30000);
            try(var binding=com.example.lms.service.chat.ChatRunExecutionContext.bind(run)){
                var first=f.sampler.prepareContext(f.original,()->{});
                assertTrue(first.prepared());
                TimeBudgetContext.set(new TimeBudget(1000));
                assertSame(first,f.sampler.prepareContext(f.original,()->{}));assertEquals(1,f.calls.get());
                TimeBudgetContext.set(new TimeBudget(30000));
                var changed=PreparedContextPacket.originals(PreparedContextPacketTest.seed(),"changed-policy");
                assertSame(changed,f.sampler.prepareContext(changed,()->{}));assertEquals(1,f.calls.get());
                registry.cancelExact(501L,run.clientToken());
                assertThrows(CancellationException.class,()->f.sampler.prepareContext(f.original,()->{}));
            }
        }finally{eviction.shutdownNow();assertTrue(eviction.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
