package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class ConversateSessionServiceTest {
    @Test void textFallbackKeepsConfirmedContextAndDeduplicationWithoutReplayingWork() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)){
            var s=service.start("owner");var info=new ConversateQuestionPolicy.Utterance("info","info",1,true,"주제 정보를 이야기합니다.");
            service.submit("owner",s.assistId(),s.epoch(),info);
            service.captureFailed("owner",s.assistId(),s.epoch(),"ASR_INPUT_LOST");
            var paused=service.status("owner",s.assistId());
            var text=service.control("owner",s.assistId(),paused.epoch(),"text_fallback");
            assertEquals("RUNNING",text.state());assertEquals("TEXT_INPUT_FALLBACK",text.reason());assertEquals("direct",text.diagnostics().inputPath());
            assertEquals(1,text.metrics().contextTurns());assertEquals(0,text.metrics().started());
            assertEquals("DUPLICATE",service.submit("owner",s.assistId(),text.epoch(),info).reason());
            assertThrows(ResponseStatusException.class,()->service.control("owner",s.assistId(),paused.epoch(),"text_fallback"));
            var userPaused=service.control("owner",s.assistId(),text.epoch(),"pause");
            assertThrows(ResponseStatusException.class,()->service.control("owner",s.assistId(),userPaused.epoch(),"text_fallback"));
            assertEquals(0,userPaused.metrics().contextTurns());
        }
    }
    @Test void asrLossRetainsFinalContextAndVerifiedCardButFencesOldCapture() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)){
            var s=service.start("owner");
            service.submit("owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("info","info",1,true,"주제 정보를 이야기합니다."));
            var card=new ConversateSessionService.Card("SHOW","FACT","검증된 합성 답변",java.util.List.of("source-7"),clock.millis()+20_000);
            assertTrue(service.publish("owner",s.assistId(),s.epoch(),card));
            var closed=new java.util.concurrent.atomic.AtomicBoolean();
            service.registerCapture("owner",s.assistId(),s.epoch(),()->closed.set(true));
            service.captureFailed("owner",s.assistId(),s.epoch(),"ASR_INPUT_LOST");
            var paused=service.status("owner",s.assistId());
            assertEquals("PAUSED",paused.state());assertTrue(closed.get());assertTrue(paused.epoch()>s.epoch());
            assertEquals(1,paused.metrics().contextTurns());assertEquals(card,paused.card());
            assertFalse(service.publish("owner",s.assistId(),s.epoch(),card));
            assertThrows(ResponseStatusException.class,()->service.submit("owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("late","late",1,true,"늦은 발화")));
            clock.advance(20_001);service.maintain();assertNull(service.status("owner",s.assistId()).card());
            clock.advance(100_000);service.maintain();assertEquals(0,service.status("owner",s.assistId()).metrics().contextTurns());
        }
    }
    @Test void outputLossKeepsTheSameCardForAuthorizedWebOutputUntilItsTtl() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)){
            var s=service.start("owner");var output=service.output("owner",s.assistId(),s.epoch()).subscribe();
            var card=new ConversateSessionService.Card("SHOW","FACT","검증된 합성 답변",java.util.List.of("source-7"),clock.millis()+20_000);
            service.publish("owner",s.assistId(),s.epoch(),card);output.dispose();clock.advance(5000);service.maintain();
            var web=service.status("owner",s.assistId());assertEquals("PAUSED",web.state());assertEquals("output_lost",web.reason());
            assertEquals(card,web.card());assertFalse(service.publish("owner",s.assistId(),s.epoch(),card));
            assertThrows(ResponseStatusException.class,()->service.status("other",s.assistId()));
            var stopped=service.control("owner",s.assistId(),web.epoch(),"stop");assertNull(stopped.card());
        }
    }
    @Test void receiptIsOwnerEpochBoundedAndCannotRefreshThroughDuplicateOrFutureAck() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)){
            var s=service.start("d".repeat(64));var output=service.output("d".repeat(64),s.assistId(),s.epoch()).subscribe();
            s=service.status("d".repeat(64),s.assistId());String id=s.assistId();long epoch=s.epoch(),version=s.version();
            var first=service.acknowledge("d".repeat(64),id,epoch,version);assertEquals(1,first.metrics().outputAcks());
            assertEquals(version,first.version());long at=first.metrics().lastOutputAckAt();clock.advance(1000);
            assertEquals(at,service.acknowledge("d".repeat(64),id,epoch,version).metrics().lastOutputAckAt());
            assertThrows(ResponseStatusException.class,()->service.acknowledge("e".repeat(64),id,epoch,version));
            assertThrows(ResponseStatusException.class,()->service.acknowledge("d".repeat(64),id,epoch,version+99));
            service.control("d".repeat(64),id,epoch,"pause");assertThrows(ResponseStatusException.class,()->service.acknowledge("d".repeat(64),id,epoch,version));output.dispose();
        }
    }
    @Test void contextIsFinalOnlyBoundedExpiresAndClearsOnPause() throws Exception {
        var contexts=new java.util.concurrent.CopyOnWriteArrayList<java.util.List<String>>();
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answerWithContext(String q,java.util.List<String> context,java.util.List<PreparedMaterialReader.Material> docs,long now){contexts.add(context);return super.answer(q,docs,now);}};
        var clock=new TestClock();try(var service=new ConversateSessionService(clock,pipeline)){
            var s=service.start("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");var output=service.output("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch()).subscribe();
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("p","p",1,false,"부분 정보"));
            for(int i=0;i<7;i++)service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("i"+i,"i"+i,1,true,"주제 "+i+"를 이야기합니다."));
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("q1","q1",1,true,"그 뜻은?"));
            for(int i=0;i<100&&contexts.isEmpty();i++)Thread.sleep(10);
            assertEquals(4,contexts.get(0).size());assertFalse(contexts.get(0).contains("부분 정보"));
            clock.advance(120_001);service.maintain();
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("q2","q2",1,true,"그 의미는?"));
            for(int i=0;i<100&&contexts.size()<2;i++)Thread.sleep(10);assertTrue(contexts.get(1).isEmpty());
            var paused=service.control("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),"pause");var resumed=service.control("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),paused.epoch(),"resume");
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),resumed.epoch(),new ConversateQuestionPolicy.Utterance("q3","q3",1,true,"그 정의는?"));
            for(int i=0;i<100&&contexts.size()<3;i++)Thread.sleep(10);assertTrue(contexts.get(2).isEmpty());output.dispose();
        }
    }
    @Test void newQuestionFencesSlowPreviousAnswerAndKeepsOnlyLatestPending() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var latestEntered=new java.util.concurrent.CountDownLatch(1);var latestRelease=new java.util.concurrent.CountDownLatch(1);
        var questions=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answer(String q,java.util.List<PreparedMaterialReader.Material> docs,long now){
            questions.add(q);var latch=questions.size()==1?release:latestRelease;
            if(questions.size()==1)entered.countDown();else latestEntered.countDown();
            boolean done=false;while(!done){try{done=latch.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
            return new Outcome("MATCH",new ConversateSessionService.Card("SHOW","FACT",q,java.util.List.of("doc"),now+20_000));
        }};
        try(var service=new ConversateSessionService(new TestClock(),pipeline)){
            var s=service.start("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");String id=s.assistId();long epoch=s.epoch();
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id,epoch,new ConversateQuestionPolicy.Utterance("a","a",1,true,"보증 기간은?"));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id,epoch,new ConversateQuestionPolicy.Utterance("b","b",1,true,"환불 조건은?"));
            service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id,epoch,new ConversateQuestionPolicy.Utterance("c","c",1,true,"배송 날짜는?"));
            assertEquals(1,service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id).metrics().queueLength());
            release.countDown();assertTrue(latestEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertNull(service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id).card(),"superseded result must never be shown while latest work runs");
            assertEquals(java.util.List.of("보증 기간은?","배송 날짜는?"),questions);
            latestRelease.countDown();
            for(int i=0;i<100&&service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id).metrics().inFlight()!=0;i++)Thread.sleep(10);
            assertEquals("배송 날짜는?",service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",id).card().text());
        }finally{release.countDown();latestRelease.countDown();}
    }
    @Test void resumedEpochHasFreshUtteranceNamespace() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answer(String q,java.util.List<PreparedMaterialReader.Material> docs,long now){calls.incrementAndGet();return super.answer(q,docs,now);}};
        try(var s=new ConversateSessionService(new TestClock(),pipeline)){
            String owner="c".repeat(64);var run=s.start(owner);
            s.submit(owner,run.assistId(),run.epoch(),new ConversateQuestionPolicy.Utterance("asr-1","asr-1",3,true,"보증 기간은 얼마인가요?"));
            for(int i=0;i<100&&calls.get()<1;i++)Thread.sleep(10);assertEquals(1,calls.get());
            var paused=s.control(owner,run.assistId(),run.epoch(),"pause");var resumed=s.control(owner,run.assistId(),paused.epoch(),"resume");
            s.submit(owner,run.assistId(),resumed.epoch(),new ConversateQuestionPolicy.Utterance("asr-1","asr-1",1,true,"보증 기간은 얼마인가요?"));
            for(int i=0;i<100&&calls.get()<2;i++)Thread.sleep(10);assertEquals(2,calls.get());
        }
    }
    @Test void onlySelectedWorkConsumesCostAndUnavailableControlRemainsUsable() throws Exception {
        var costs=new java.util.concurrent.atomic.AtomicInteger();var checked=new java.util.concurrent.CountDownLatch(1);
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answer(String q,java.util.List<PreparedMaterialReader.Material> docs,long now){fail("denied admission must precede processing");return null;}};
        try(var service=new ConversateSessionService(new TestClock(),pipeline)){
            String owner="b".repeat(64);var s=service.start(owner,()->{costs.incrementAndGet();checked.countDown();throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"chat_admission_unavailable");});
            service.status(owner,s.assistId());var output=service.output(owner,s.assistId(),s.epoch()).subscribe();output.dispose();
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("b","b",1,true,"아, 네"));assertEquals(0,costs.get());
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("q","q",1,true,"보증 기간은 얼마인가요?"));assertTrue(checked.await(2,java.util.concurrent.TimeUnit.SECONDS));
            for(int i=0;i<100&&service.status(owner,s.assistId()).metrics().inFlight()!=0;i++)Thread.sleep(10);
            assertEquals(1,costs.get());assertEquals(0,service.status(owner,s.assistId()).metrics().started());assertEquals("ADMISSION_UNAVAILABLE",service.status(owner,s.assistId()).reason());
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("q","q",1,true,"보증 기간은 얼마인가요?"));assertEquals(1,costs.get());
            assertEquals("STOPPED",service.control(owner,s.assistId(),s.epoch(),"stop").state());
        }
    }
    @Test void correctionWaitsForActualOldWorkerExitEvenWhenInterruptIsIgnored() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var second=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answer(String q,java.util.List<PreparedMaterialReader.Material> docs,long now){
            if(calls.incrementAndGet()==1){entered.countDown();boolean done=false;while(!done){try{done=release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}}}else second.countDown();return super.answer(q,docs,now);
        }};
        try(var service=new ConversateSessionService(new TestClock(),pipeline)){
            String owner="b".repeat(64);var s=service.start(owner);String id=s.assistId();long epoch=s.epoch();
            service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u","q",1,true,"보증 기간은 2년인가요?"));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u","q",2,true,"보증 기간은 3년인가요?"));
            assertFalse(second.await(200,java.util.concurrent.TimeUnit.MILLISECONDS),"old worker must physically exit before next generation");
            release.countDown();assertTrue(second.await(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(2,calls.get());
        }finally{release.countDown();}
    }
    @Test void textFixtureUsesOneBoundedWorkerAndStopFencesLateComputation() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var clock=new TestClock();var pipeline=new ConversateAnswerPipeline(){
            @Override public Outcome answer(String question,java.util.List<PreparedMaterialReader.Material> docs,long now){entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}return super.answer(question,docs,now);}
        };
        try(var service=new ConversateSessionService(clock,pipeline)) {
            String owner="a".repeat(64);var s=service.start(owner);
            s=service.prepare(owner,s.assistId(),s.epoch(),java.util.List.of(new PreparedMaterialReader.Material("doc","보증 기간은 2년입니다.")));
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u1","q1",1,true,"보증 기간은 얼마인가요?"));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("b","b",1,true,"아, 네"));
            for(int i=2;i<8;i++)service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u"+i,"q"+i,1,true,"보증 기간은 얼마인가요?"));
            assertTrue(service.status(owner,s.assistId()).metrics().queueLength()<=2);
            var stop=service.control(owner,s.assistId(),s.epoch(),"stop");release.countDown();assertEquals(0,stop.metrics().queueLength());assertEquals(0,stop.metrics().preparedSources());assertEquals(0,service.sessionCount());
        }finally{release.countDown();}
    }
    static final class TestClock extends Clock {
        Instant now=Instant.parse("2026-09-12T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return now;}
        void advance(long millis){now=now.plusMillis(millis);}
    }
    @Test void onlyLastOutputStartsGraceAndDiagnosticReadDoesNotExtendIt() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)) {
            var s=service.start("owner");
            var a=service.output("owner",s.assistId(),s.epoch()).subscribe();
            var b=service.output("owner",s.assistId(),s.epoch()).subscribe();
            a.dispose();clock.advance(6000);service.maintain();
            assertEquals("RUNNING",service.status("owner",s.assistId()).state());
            b.dispose();clock.advance(4999);service.maintain();
            assertEquals("RUNNING",service.status("owner",s.assistId()).state());
            clock.advance(1);service.maintain();
            assertEquals("PAUSED",service.status("owner",s.assistId()).state());
        }
    }
    @Test void reconnectWithinGraceKeepsSessionButNeverResumesPausedSession() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)) {
            var s=service.start("owner");var a=service.output("owner",s.assistId(),s.epoch()).subscribe();a.dispose();
            clock.advance(4000);var b=service.output("owner",s.assistId(),s.epoch()).subscribe();
            clock.advance(6000);service.maintain();assertEquals("RUNNING",service.status("owner",s.assistId()).state());
            b.dispose();clock.advance(5000);service.maintain();
            var paused=service.status("owner",s.assistId());
            var c=service.output("owner",s.assistId(),paused.epoch()).subscribe();
            assertEquals("PAUSED",service.status("owner",s.assistId()).state());c.dispose();
        }
    }
    @Test void stopDiscardsCardAndRejectsOldEpochAndOtherOwner() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)) {
            var s=service.start("owner");
            assertTrue(service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","FACT","테스트 답변 1",java.util.List.of(),clock.instant().plusSeconds(20).toEpochMilli())));
            assertThrows(ResponseStatusException.class,()->service.status("other",s.assistId()));
            var stopped=service.control("owner",s.assistId(),s.epoch(),"stop");assertNull(stopped.card());
            assertEquals("STOPPED",stopped.state());assertTrue(stopped.epoch()>s.epoch());
            assertFalse(service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","FACT","late",java.util.List.of(),Long.MAX_VALUE)));
            assertThrows(ResponseStatusException.class,()->service.status("owner",s.assistId()));
            assertEquals(0,service.sessionCount());
        }
    }
    @Test void expiredCardIsRemovedAndOwnerSessionsAreBounded() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)) {
            var s=service.start("owner");service.start("owner");
            assertThrows(ResponseStatusException.class,()->service.start("owner"));
            service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","FACT","검증 카드",java.util.List.of(),clock.instant().plusSeconds(1).toEpochMilli()));
            clock.advance(1000);service.maintain();assertNull(service.status("owner",s.assistId()).card());
            clock.advance(3_600_000);service.maintain();service.maintain();assertEquals(0,service.sessionCount());
        }
    }
    @Test void pauseFencesLateResultsAndDoesNotResumeOnOutputAttach() {
        var clock=new TestClock();try(var service=new ConversateSessionService(clock)) {
            var s=service.start("owner");var p=service.control("owner",s.assistId(),s.epoch(),"pause");
            assertTrue(p.epoch()>s.epoch());
            assertFalse(service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","FACT","late",java.util.List.of(),Long.MAX_VALUE)));
            assertThrows(ResponseStatusException.class,()->service.control("owner",s.assistId(),s.epoch(),"resume"));
            assertEquals("RUNNING",service.control("owner",s.assistId(),p.epoch(),"resume").state());
        }
    }
}
