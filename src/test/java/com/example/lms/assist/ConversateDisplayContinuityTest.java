package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateDisplayContinuityTest {
    static class ClockStub extends Clock {
        long time=1_000_000;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(time);}
        public long millis(){return time;}
    }
    @Test void transientAsrLossPreservesPublicSessionCaptionAndHintBeforeContinuation(){
        var clock=new ClockStub();try(var service=new ConversateSessionService(clock)){
            var s=service.startPublicDisplay("owner");
            service.pollOutput("owner",s.assistId(),s.epoch(),"a".repeat(32));
            service.registerCapture("owner",s.assistId(),s.epoch(),()->{});
            service.submit("owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("speech","speech",1,true,"대화를 계속 이어갑니다."),"phone_voice",null);
            service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","CUE","짧은 힌트",List.of(),clock.millis()+100000));
            var before=service.status("owner",s.assistId());
            service.captureFailed("owner",s.assistId(),s.epoch(),"ASR_INPUT_LOST");
            var waiting=service.status("owner",s.assistId());
            assertEquals("RUNNING",waiting.state());assertEquals("WAITING",waiting.audio().state());
            assertEquals(before.epoch(),waiting.epoch());assertEquals(before.caption(),waiting.caption());assertEquals(before.card(),waiting.card());
            var resumed=service.nextSegment("owner",s.assistId(),waiting.epoch());
            assertEquals(s.assistId(),resumed.assistId());assertEquals(before.caption(),resumed.caption());assertEquals(before.card(),resumed.card());
            assertEquals(before.metrics().contextTurns(),resumed.metrics().contextTurns());assertTrue(resumed.epoch()>waiting.epoch());
        }
    }
    @Test void publicCaptureSurvivesTemporaryOutputGapAndUsesConfiguredHundredSecondTextLifetime(){
        var clock=new ClockStub();try(var service=new ConversateSessionService(clock)){
            ReflectionTestUtils.setField(service,"displayTtlMs",100000L);
            var s=service.startPublicDisplay("owner");var closes=new AtomicInteger();
            service.pollOutput("owner",s.assistId(),s.epoch(),"a".repeat(32));
            service.registerCapture("owner",s.assistId(),s.epoch(),closes::incrementAndGet);
            var heard=service.submit("owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("speech","speech",1,true,"대화를 계속 이어갑니다."),"phone_voice",null);
            assertEquals(clock.millis()+100000,heard.caption().expiresAt());
            clock.time+=6000;service.maintain();clock.time+=15000;service.maintain();
            var waiting=service.status("owner",s.assistId());assertEquals("RUNNING",waiting.state());assertEquals(s.epoch(),waiting.epoch());assertNotNull(waiting.caption());assertEquals(0,closes.get());
        }
    }
    @Test void providerLimitDoesNotEraseOrEndPublicSession(){
        var clock=new ClockStub();try(var service=new ConversateSessionService(clock)){
            var s=service.startPublicDisplay("owner");service.registerCapture("owner",s.assistId(),s.epoch(),()->{});
            service.captureFailed("owner",s.assistId(),s.epoch(),"ASR_QUOTA_EXCEEDED");
            var waiting=service.status("owner",s.assistId());assertEquals("RUNNING",waiting.state());assertEquals("API_PAUSED",waiting.audio().state());assertEquals(s.epoch(),waiting.epoch());
        }
    }
}
