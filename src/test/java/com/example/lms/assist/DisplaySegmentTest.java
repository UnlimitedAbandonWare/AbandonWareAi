package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import java.time.Clock;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;
class DisplaySegmentTest {
    @Test void rolloverFencesOldAudioButKeepsPendingHintUntilNewUtterance() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pipeline=new ConversateAnswerPipeline(){
            @Override boolean usesApiCues(){return true;}
            @Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String request){
                entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                return new Outcome("API_CUE",new ConversateSessionService.Card("SHOW","CUE","delayed hint",List.of(),now+15000,request,List.of()));
            }
        };
        try(var service=new ConversateSessionService(Clock.systemUTC(),pipeline)){
            String owner="a".repeat(64);var s=service.startPublicDisplay(owner);String id=s.assistId();long epoch=s.epoch();
            service.pollOutput(owner,id,epoch,"b".repeat(32));
            service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("asr-1","asr-1",1,true,"설계 경험을 어떻게 설명할까요?"),"phone_voice",null);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            service.audioMetrics(owner,id,epoch,new ConversateSessionService.AudioMetrics(1,0,1,0,0,"STOPPED",Map.of("stopReason","finished")));
            var next=service.nextSegment(owner,id,epoch);assertEquals(epoch+1,next.epoch());assertNotNull(next.caption());
            assertThrows(ResponseStatusException.class,()->service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("old","old",2,true,"late"),"phone_voice",null));
            release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(service.status(owner,id).card()==null&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals("delayed hint",service.status(owner,id).card().text());
            service.submit(owner,id,next.epoch(),new ConversateQuestionPolicy.Utterance("asr-1","asr-1",1,false,"새 구간"),"phone_voice",null);
            assertNull(service.status(owner,id).card());assertEquals("새 구간",service.status(owner,id).caption().text());
        } finally {release.countDown();}
    }
}
