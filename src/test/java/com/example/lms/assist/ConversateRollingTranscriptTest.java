package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class ConversateRollingTranscriptTest {
    static final String OWNER="a".repeat(64),CLIENT="b".repeat(32);
    static class Time extends Clock {long value=100000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(value);}}
    static class Pipe extends ConversateAnswerPipeline {volatile List<String> context=List.of();final CountDownLatch called=new CountDownLatch(1);final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        @Override boolean usesApiCues(){return true;}
        @Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String id){calls.incrementAndGet();context=List.copyOf(c);called.countDown();return new Outcome("API_CUE",new ConversateSessionService.Card("SHOW","CUE","확인된 짧은 힌트",List.of(),now+15000,id,List.of()));}}
    static ConversateSessionService service(Time time,Pipe pipe,int minDelta){var s=new ConversateSessionService(time,pipe);ReflectionTestUtils.setField(s,"rollingEnabled",true);ReflectionTestUtils.setField(s,"triggerMinDeltaChars",minDelta);return s;}
    static ConversateSessionService.Snapshot say(ConversateSessionService s,ConversateSessionService.Snapshot session,int n,boolean fin,String text){return s.submit(OWNER,session.assistId(),session.epoch(),new ConversateQuestionPolicy.Utterance("u"+n,"u"+n,fin?2:1,fin,text),"phone_voice",null);}
    static void waitForCalls(Pipe pipe,int n) throws InterruptedException {for(int i=0;i<200&&pipe.calls.get()<n;i++)Thread.sleep(10);}
    static void waitForCard(ConversateSessionService s,ConversateSessionService.Snapshot x) throws InterruptedException {for(int i=0;i<200&&s.status(OWNER,x.assistId()).card()==null;i++)Thread.sleep(10);}
    @Test void twoFinalsAndPartialRemainAfterHintExpiryAndHourBoundary(){var time=new Time();try(var s=service(time,new Pipe(),40)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);var closed=new java.util.concurrent.atomic.AtomicInteger();s.registerCapture(OWNER,x.assistId(),x.epoch(),closed::incrementAndGet);
        say(s,x,1,true,"하이젠베르크 불확정성 원리가 뭐야?");var second=say(s,x,2,true,"누구세요?");assertEquals("하이젠베르크 불확정성 원리가 뭐야?\n누구세요?",second.caption().text());
        s.publish(OWNER,x.assistId(),x.epoch(),new ConversateSessionService.Card("SHOW","CUE","힌트",List.of(),time.value+15000));
        time.value+=16000;s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();assertNull(s.status(OWNER,x.assistId()).card());assertEquals(second.caption().text(),s.status(OWNER,x.assistId()).caption().text());assertEquals(0,closed.get());
        time.value+=7200000;s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();assertEquals("RUNNING",s.status(OWNER,x.assistId()).state());assertEquals(0,closed.get());
        assertTrue(say(s,x,3,false,"지금 말하는 중").caption().text().endsWith("누구세요?\n지금 말하는 중"));
    }}
    @Test void controllerAcceptsConfiguredStreamRenewalAndSameOwnerAfterTwoHours(){var time=new Time();try(var s=service(time,new Pipe(),40)){
        var owners=org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);org.mockito.Mockito.when(owners.ownerKey()).thenReturn("synthetic-owner");
        var address=org.mockito.Mockito.mock(InterviewDemoPublicAddress.class);var asr=org.mockito.Mockito.mock(ConversateAsrBridge.class);
        org.mockito.Mockito.when(asr.available()).thenReturn(true);org.mockito.Mockito.when(asr.renewAfterMs()).thenReturn(65000L);
        var c=new DisplayConversateController(s,owners,address,time);ReflectionTestUtils.setField(c,"asr",asr);ReflectionTestUtils.setField(c,"audioEnabled",true);ReflectionTestUtils.setField(c,"phoneTestEnabled",true);
        var http=new org.springframework.mock.web.MockHttpServletRequest();http.setMethod("POST");http.setScheme("http");http.setServerName("localhost");http.setServerPort(80);http.setRemoteAddr("127.0.0.1");http.addHeader("Origin","http://localhost");http.addHeader("X-Display-Client","1");
        var v=c.phoneTest(new DisplayConversateController.Connection(null,0,CLIENT,true,false),http).getBody();String owner=org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-owner");
        c.audioStart(new DisplayConversateController.Connection(v.assistId(),v.epoch(),CLIENT),http);
        s.audioMetrics(owner,v.assistId(),v.epoch(),new ConversateSessionService.AudioMetrics(1,0,1,0,0,"STOPPED",Map.of("stopReason","finished")));
        time.value+=65000;var next=c.audioStart(new DisplayConversateController.Connection(v.assistId(),v.epoch(),CLIENT,false,true),http).getBody();assertEquals(v.epoch()+1,next.epoch());assertEquals(65000,next.audioRenewAfterMs());
        for(int i=0;i<130;i++){time.value+=60000;c.poll(new DisplayConversateController.Connection(next.assistId(),next.epoch(),CLIENT),http);s.maintain();}
        assertEquals(v.assistId(),c.phoneTest(new DisplayConversateController.Connection(next.assistId(),next.epoch(),CLIENT),http).getBody().assistId());
    }}
    @Test void repeatedProviderUtteranceIdAfterRenewalDoesNotReplacePreviousConversation(){var time=new Time();try(var s=service(time,new Pipe(),40)){
        var x=s.startPublicDisplay(OWNER);say(s,x,1,true,"첫 번째 연결의 확정 전사");
        s.audioMetrics(OWNER,x.assistId(),x.epoch(),new ConversateSessionService.AudioMetrics(1,0,1,0,0,"STOPPED",Map.of("stopReason","finished")));
        var next=s.nextSegment(OWNER,x.assistId(),x.epoch());assertEquals("첫 번째 연결의 확정 전사\n새 연결의 확정 전사",say(s,next,1,true,"새 연결의 확정 전사").caption().text());
    }}
    @Test void quotaFailurePreservesSessionEpochAndRollingTextAcrossTwoHours(){var time=new Time();try(var s=service(time,new Pipe(),40)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);say(s,x,1,true,"이미 확정된 발화는 API 오류로 지우지 않습니다.");
        s.registerCapture(OWNER,x.assistId(),x.epoch(),()->{});s.captureFailed(OWNER,x.assistId(),x.epoch(),"ASR_QUOTA_EXCEEDED");
        var paused=s.status(OWNER,x.assistId());assertEquals("RUNNING",paused.state());assertEquals("API_PAUSED",paused.audio().state());assertEquals(x.epoch(),paused.epoch());assertNotNull(paused.caption());
        time.value+=7200001;s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();assertEquals("RUNNING",s.status(OWNER,x.assistId()).state());assertEquals(paused.caption().text(),s.status(OWNER,x.assistId()).caption().text());
        s.control(OWNER,x.assistId(),x.epoch(),"stop");assertEquals(0,s.sessionCount());
    }}
    @Test void evictsWholeVisibleUtterancesButRetainsLongerContextAndLatestOversize(){var time=new Time();try(var s=service(time,new Pipe(),40)){
        var x=s.startPublicDisplay(OWNER);s.control(OWNER,x.assistId(),x.epoch(),"hints_off");
        for(int i=1;i<=12;i++)say(s,x,i,true,"문장"+i+"가".repeat(75));
        var v=s.status(OWNER,x.assistId()).caption().text();assertFalse(v.contains("문장9"));assertTrue(v.contains("문장11"));assertTrue(v.contains("문장12"));assertTrue(v.length()<=200);
        var d=s.transcriptDiagnostics(OWNER,x.assistId());assertTrue((int)d.get("contextChars")>v.length());assertTrue((int)d.get("contextChars")<=1000);
        assertEquals("최신"+"나".repeat(240),say(s,x,13,true,"최신"+"나".repeat(240)).caption().text());
        assertTrue(say(s,x,14,false,"현재 partial").caption().text().endsWith("\n현재 partial"));
    }}
    @Test void finalEventSamplingHonorsCooldownAndPassesPriorContext() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,30)){
        var x=s.startPublicDisplay(OWNER);s.control(OWNER,x.assistId(),x.epoch(),"hints_off");say(s,x,1,true,"화면에서 지워져도 문맥에는 남아야 하는 첫 대화입니다.");s.control(OWNER,x.assistId(),x.epoch(),"hints_on");
        say(s,x,2,false,"후속 질문");assertEquals(1,pipe.called.getCount());say(s,x,2,true,"그 대화를 조금 더 설명해 줄 수 있나요?");assertTrue(pipe.called.await(2,TimeUnit.SECONDS));assertEquals(List.of("화면에서 지워져도 문맥에는 남아야 하는 첫 대화입니다."),pipe.context);
        waitForCard(s,x);say(s,x,3,true,"연속 발화는 재호출하면 안 됩니다.");String events=s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString();assertTrue(events.contains("CUE_TRIGGERED"));assertTrue(events.contains("display_hold"));assertEquals(1,pipe.calls.get());assertFalse(events.contains("첫 대화"));
    }}
    @Test void accumulatedTranscriptDeltaDuringQuietFiresBeforeWatchdog() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,40)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
        say(s,x,1,true,"짧지만 의미 있는 첫 번째 발화가 기록됩니다.");
        say(s,x,2,false,"그리고 중간 전사가 이어지면서 내용이 조금 더 쌓입니다.");
        time.value+=3000;s.maintain();assertEquals(0,pipe.calls.get());
        time.value+=3000;s.maintain();assertTrue(pipe.called.await(2,TimeUnit.SECONDS));
        String events=s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString();assertTrue(events.contains("ACCUM_HINT_TRIGGERED"));assertTrue(events.contains("transcript_delta"));
    }}
    @Test void growingTranscriptDoesNotTriggerUntilSpeechPauses() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,30)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
        say(s,x,1,false,"아직 말하는 중인 전사가 충분히 길게 이어지고 있습니다.");s.maintain();
        assertEquals(0,pipe.calls.get());assertFalse(s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString().contains("ACCUM_HINT_TRIGGERED"));
        time.value+=3000;s.maintain();waitForCalls(pipe,1);assertEquals(1,pipe.calls.get());
    }}
    @Test void hintDisplayHoldBlocksNewTriggersUntilCardExpires() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,30)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
        say(s,x,1,true,"충분한 길이의 첫 번째 발화가 새로운 정보를 누적합니다.");assertTrue(pipe.called.await(2,TimeUnit.SECONDS));waitForCard(s,x);
        assertEquals(time.value+15000,s.status(OWNER,x.assistId()).card().expiresAt());
        say(s,x,2,true,"힌트 표시 중에 들어온 두 번째 발화도 차단됩니다.");
        say(s,x,3,true,"세 번째 발화도 새 힌트를 만들지 않습니다.");
        assertEquals(1,pipe.calls.get());assertTrue(s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString().contains("display_hold"));
        time.value+=16000;s.maintain();assertNull(s.status(OWNER,x.assistId()).card());
        time.value+=3000;s.maintain();waitForCalls(pipe,2);assertEquals(2,pipe.calls.get());
        assertTrue(s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString().contains("transcript_delta"));
    }}
    @Test void watchdogStillFiresWhenDeltaStaysBelowAccumThreshold() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,80)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
        say(s,x,1,true,"오늘 회의에서 논의한 내용을 정리하면 다음 주 발표 준비와 자료 검토가 필요한 상황입니다.");
        time.value+=181000;s.maintain();assertTrue(pipe.called.await(2,TimeUnit.SECONDS));
        String events=s.transcriptDiagnostics(OWNER,x.assistId()).get("events").toString();assertTrue(events.contains("FORCE_HINT_TRIGGERED"));assertTrue(events.contains("3min_watchdog"));
    }}
    @Test void hintsOnReanchorsBaselineSoPriorTranscriptIsNotNewDelta() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe,30)){
        var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
        s.control(OWNER,x.assistId(),x.epoch(),"hints_off");
        say(s,x,1,true,"힌트가 꺼져 있는 동안 쌓인 충분히 긴 이전 대화 내용입니다.");
        say(s,x,2,true,"계속 이어지는 이전 발화도 충분한 분량을 가지고 있습니다.");
        s.control(OWNER,x.assistId(),x.epoch(),"hints_on");s.maintain();
        time.value+=3000;s.maintain();assertEquals(0,pipe.calls.get());
        say(s,x,3,true,"다시 켜진 뒤에 새로 누적되는 충분히 긴 발화 내용이 이어집니다.");assertTrue(pipe.called.await(2,TimeUnit.SECONDS));assertEquals(1,pipe.calls.get());
    }}
    @Test void shippedDefaultsRequire120NewCharsAnd2500MsQuiet() throws Exception {var time=new Time();var pipe=new Pipe();
        try(var s=new ConversateSessionService(time,pipe)){ReflectionTestUtils.setField(s,"rollingEnabled",true);
            var x=s.startPublicDisplay(OWNER);s.pollOutput(OWNER,x.assistId(),x.epoch(),CLIENT);s.maintain();
            say(s,x,1,false,"가".repeat(119));s.maintain();
            time.value+=3000;s.maintain();assertEquals(0,pipe.calls.get());
            say(s,x,2,false,"가".repeat(119)+"나");s.maintain();
            time.value+=2499;s.maintain();assertEquals(0,pipe.calls.get());
            time.value+=1;s.maintain();waitForCalls(pipe,1);assertEquals(1,pipe.calls.get());
        }
    }
}
