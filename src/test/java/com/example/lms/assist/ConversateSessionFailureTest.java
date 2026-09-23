package com.example.lms.assist;

import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateSessionFailureTest {
    @Test void publicApiFailurePublishesOneSafeTerminalCard() throws Exception {
        var failures=List.of(LlmFailureClass.AUTH_MISSING,LlmFailureClass.RATE_LIMIT_COOLDOWN,LlmFailureClass.TIMEOUT_SOFT);
        var reasons=List.of("GENERATION_DENIED","GENERATION_RATE_LIMITED","GENERATION_TIMEOUT");
        for(int i=0;i<failures.size();i++){
            var failure=failures.get(i);var calls=new AtomicInteger();var release=new java.util.concurrent.CountDownLatch(1);
            var pipeline=new ConversateAnswerPipeline(){
                @Override public Outcome answerPublicDisplay(String question,List<String> context,long now,String requestId){
                    calls.incrementAndGet();try{if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("fixture_timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}throw new LlmGatewayException("synthetic-private-body",failure);
                }
            };
            try(var service=new ConversateSessionService(Clock.systemUTC(),pipeline)){
                String owner="a".repeat(64);var started=service.startPublicDisplay(owner);
                var input=new ConversateQuestionPolicy.Utterance("one","one",1,true,"합성 질문");
                service.submit(owner,started.assistId(),started.epoch(),input,"glasses_input","request-one");
                service.submit(owner,started.assistId(),started.epoch(),input,"glasses_input","request-one");
                release.countDown();long deadline=System.nanoTime()+2_000_000_000L;ConversateSessionService.Snapshot state;
                do{state=service.status(owner,started.assistId());if(state.metrics().inFlight()==0)break;Thread.sleep(10);}while(System.nanoTime()<deadline);
                assertEquals(1,calls.get());assertEquals(1,state.metrics().duplicates());
                assertEquals(reasons.get(i),state.reason());assertNotNull(state.card());assertEquals("ASK",state.card().decision());
                assertEquals("request-one",state.card().requestId());assertFalse(state.card().text().contains("synthetic-private-body"));
                assertEquals("OFF",state.audio().state());
            }
        }
    }
}
