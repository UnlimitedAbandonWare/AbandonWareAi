package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateCaptureRestartTest {
    @Test void newCaptureAcceptsReusedProviderIdWhileDuplicatesAndOldEpochStayRejected() throws Exception {
        var calls=new AtomicInteger();
        var generator=new ConversateLocalCardGenerator((messages,schema)->{calls.incrementAndGet();return "{\"choice\":3}";},4000);
        try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),new ConversateAnswerPipeline(generator))){
            var json=new ObjectMapper();
            var bridge=new ConversateAsrBridge(service,json,(events,failure)->{
                events.accept(json.createObjectNode().put("type","ready"));
                return new ConversateAsrBridge.Transport(){boolean closed;
                    public void send(String line){} public boolean alive(){return !closed;}
                    public CompletableFuture<Void> finish(){return CompletableFuture.completedFuture(null);}
                    public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}
                };
            });
            try{
                String owner="e".repeat(64);var first=service.start(owner);String id=first.assistId();long epoch=first.epoch();
                service.pollOutput(owner,id,epoch,"d".repeat(32));bridge.start(owner,id,epoch);
                var finalSpeech=new ConversateQuestionPolicy.Utterance("dg-0","dg-0",2,true,"다음 행동을 결정하기 어렵습니다.");
                service.submit(owner,id,epoch,finalSpeech,"phone_voice",null);
                awaitHint(service,owner,id);assertEquals(1,calls.get());
                bridge.finish(owner,id,epoch);
                long next=service.control(owner,id,epoch,"text_fallback").epoch();
                service.pollOutput(owner,id,next,"d".repeat(32));bridge.start(owner,id,next);
                var partial=service.submit(owner,id,next,new ConversateQuestionPolicy.Utterance("dg-0","dg-0",1,false,"다음 행동을"),"phone_voice",null);
                assertNotNull(partial.caption(),"fresh capture must not inherit prior finalized ID");
                assertFalse(partial.caption().isFinal());assertEquals(1,calls.get());
                service.submit(owner,id,next,finalSpeech,"phone_voice",null);
                awaitHint(service,owner,id);assertEquals(2,calls.get());
                service.submit(owner,id,next,finalSpeech,"phone_voice",null);
                assertEquals(2,calls.get());assertEquals(1,service.status(owner,id).metrics().duplicates());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.submit(owner,id,epoch,finalSpeech,"phone_voice",null));
                assertEquals(finalSpeech.text(),service.status(owner,id).caption().text());
                bridge.finish(owner,id,next);assertEquals(0,bridge.activeCount());
            }finally{bridge.close();}
        }
    }
    private static void awaitHint(ConversateSessionService service,String owner,String id)throws Exception{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(service.status(owner,id).card()==null&&System.nanoTime()<deadline)Thread.sleep(10);
        assertNotNull(service.status(owner,id).card(),service.status(owner,id).reason());
    }
    public static void main(String[] args)throws Exception{
        new ConversateCaptureRestartTest().newCaptureAcceptsReusedProviderIdWhileDuplicatesAndOldEpochStayRejected();
        System.out.println("restart-regression=PASS");
    }
}
