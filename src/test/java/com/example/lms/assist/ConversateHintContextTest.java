package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Settings-driven past-context window for rolling hint input: enable, window,
    char/token caps, explicit reset, topic-change cutoff, stale-result fencing. */
class ConversateHintContextTest {
    static final String OWNER="a".repeat(64),CLIENT="b".repeat(32);
    static class Time extends Clock {long value=100000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(value);}}
    static class Pipe extends ConversateAnswerPipeline {volatile List<String> context=List.of();final CountDownLatch called=new CountDownLatch(1);final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        @Override boolean usesApiCues(){return true;}
        @Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String id){calls.incrementAndGet();context=List.copyOf(c);called.countDown();return new Outcome("API_CUE",new ConversateSessionService.Card("SHOW","CUE","확인된 짧은 힌트",List.of(),now+15000,id,List.of()),0,0,0,0,0,new Stages(null,null,null,null,null,null,null,null,Map.of("topicChanged",q.contains("새 주제"))));}}
    static ConversateSessionService service(Time time,Pipe pipe){var s=new ConversateSessionService(time,pipe);ReflectionTestUtils.setField(s,"rollingEnabled",true);ReflectionTestUtils.setField(s,"triggerMinDeltaChars",20);return s;}
    static ConversateSessionService.Snapshot say(ConversateSessionService s,ConversateSessionService.Snapshot session,int n,boolean fin,String text){return s.submit(OWNER,session.assistId(),session.epoch(),new ConversateQuestionPolicy.Utterance("u"+n,"u"+n,fin?2:1,fin,text),"phone_voice",null);}
    static ConversateSessionService.Snapshot sayAsr(ConversateSessionService s,ConversateSessionService.Snapshot session,int n,int revision,boolean fin,String text){return s.submit(OWNER,session.assistId(),session.epoch(),new ConversateQuestionPolicy.Utterance("asr-"+n,"asr-"+n,revision,fin,text),"phone_voice",null);}
    static void waitForCalls(Pipe pipe,int n) throws InterruptedException {for(int i=0;i<200&&pipe.calls.get()<n;i++)Thread.sleep(10);}
    /** Durable output presence: pollOutput entries expire after the 5 s grace window. */
    static reactor.core.Disposable output(ConversateSessionService s,ConversateSessionService.Snapshot x){return s.output(OWNER,x.assistId(),x.epoch()).subscribe();}
    /** hintTtl 15 s keeps the 16 s advance idiom of the rolling tests; other fields default. */
    static LensDisplayPrefs prefs(Boolean enabled,Long windowMs,Integer chars,Integer tokens,Boolean topicReset){
        return LensDisplayPrefs.defaults(600).patch(new LensDisplayPrefs.Patch(null,null,null,null,null,15_000L,null,null,enabled,windowMs,chars,tokens,topicReset,null,null,null));}

    @Test void historyOffSendsOnlyCurrentUtterance() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(false,null,null,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링 상태와 블루투스 연결 절차를 확인하는 중입니다.");waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"지금 배고픈데 근처에 돈가스 먹을 만한 곳이 있을까?");waitForCalls(pipe,2);
        assertTrue(pipe.context.isEmpty(),"history disabled must send no past turns");
    }}
    @Test void historyWindowDropsOldTurnsAndKeepsFollowUp() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,30_000L,null,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"배가 고픈데 점심으로 돈가스를 먹으러 가볼까 합니다.");waitForCalls(pipe,2);
        assertTrue(pipe.context.stream().anyMatch(t->t.contains("안경")),"16 s old turn must survive a 30 s window");
        time.value+=16_000;s.maintain();
        say(s,x,3,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,3);
        assertTrue(pipe.context.stream().anyMatch(t->t.contains("돈가스")),"16 s old follow-up context preserved");
        assertFalse(pipe.context.stream().anyMatch(t->t.contains("안경")||t.contains("블루투스")),"32 s old turn exceeds the 30 s window");
    }}
    @Test void historyCharsCapKeepsNewestAndTruncatesOldestTail() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,300,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"가".repeat(400));waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"배가 고픈데 점심으로 돈가스를 먹으러 가볼까 합니다.");waitForCalls(pipe,2);
        time.value+=16_000;s.maintain();
        say(s,x,3,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,3);
        assertEquals(2,pipe.context.size());
        assertEquals(300-pipe.context.get(1).length(),pipe.context.get(0).length(),"oldest kept turn head-truncated to remaining budget");
        assertTrue(pipe.context.get(1).contains("돈가스"),"newest turn kept whole");
        assertTrue(pipe.context.get(0).length()<400,"oldest turn was truncated");
    }}
    @Test void historyTokensCapDropsOversizedTurns() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,null,100,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"나".repeat(400));waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"배가 고픈데 점심으로 돈가스를 먹으러 가볼까 합니다.");waitForCalls(pipe,2);
        time.value+=16_000;s.maintain();
        say(s,x,3,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,3);
        assertEquals(1,pipe.context.size());assertTrue(pipe.context.get(0).contains("돈가스"),"only the turn fitting the token cap survives");
    }}
    @Test void contextResetExcludesPastTurnsAndKeepsTranscript() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,null,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");waitForCalls(pipe,1);
        var reset=s.control(OWNER,x.assistId(),x.epoch(),"context_reset");
        assertEquals("CONTEXT_RESET",reset.reason());assertEquals(0,reset.metrics().contextTurns());assertNull(reset.card());
        assertNotNull(reset.caption(),"rolling transcript must survive a context reset");
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,2);
        assertFalse(pipe.context.stream().anyMatch(t->t.contains("안경")||t.contains("블루투스")),"pre-reset turns never re-enter hint input");
        var history=(Map<String,Object>)s.transcriptDiagnostics(OWNER,x.assistId()).get("history");
        assertEquals(2L,((Number)history.get("contextEpoch")).longValue());
        var last=(Map<String,Object>)s.transcriptDiagnostics(OWNER,x.assistId()).get("lastContextSelection");
        assertTrue(last.containsKey("turns")&&last.containsKey("chars")&&last.containsKey("estTokens"));
    }}
    @Test void contextResetDropsDelayedPreResetTranscript() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,null,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        sayAsr(s,x,1,1,false,"안경 페어링 상태를 확인하는 중이라는 부분 전사입니다.");assertNotNull(s.status(OWNER,x.assistId()).caption());
        var reset=s.control(OWNER,x.assistId(),x.epoch(),"context_reset");assertEquals("CONTEXT_RESET",reset.reason());
        long duplicates=s.status(OWNER,x.assistId()).metrics().duplicates();
        sayAsr(s,x,1,2,true,"안경 페어링과 블루투스 연결 상태를 점검하는 늦은 최종 전사입니다.");
        sayAsr(s,x,1,3,false,"안경 페어링의 늦은 부분 전사도 버립니다.");
        var status=s.status(OWNER,x.assistId());
        assertEquals(duplicates+2,status.metrics().duplicates(),"delayed pre-reset events count as stale");
        assertNotNull(status.caption());assertFalse(status.caption().text().contains("늦은"),"delayed pre-reset transcript must not repaint");
        var d=s.transcriptDiagnostics(OWNER,x.assistId());
        assertEquals(0,((Number)d.get("contextTurns")).intValue(),"pre-reset utterance must not re-enter context");
        var events=(List<Map<String,Object>>)d.get("events");
        assertTrue(events.stream().anyMatch(e->"CONTEXT_STALE_AUDIO".equals(e.get("event"))),"stale drop leaves a redacted diagnostic");
        time.value+=16_000;s.maintain();
        sayAsr(s,x,2,1,true,"배가 고픈데 점심으로 돈가스를 먹으러 가볼까 합니다.");waitForCalls(pipe,1);
        assertFalse(pipe.context.stream().anyMatch(t->t.contains("안경")),"post-reset hint input excludes the delayed pre-reset utterance");
    }}
    @Test void contextResetFencesInflightResult() throws Exception {var time=new Time();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pipe=new Pipe(){@Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String id){calls.incrementAndGet();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return super.answerPublicDisplay(q,c,now,id);}};
        try(var s=service(time,pipe)){
            s.displayPrefs(owner->prefs(true,null,null,null,null));
            var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
            say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");assertTrue(entered.await(2,TimeUnit.SECONDS));
            s.control(OWNER,x.assistId(),x.epoch(),"context_reset");release.countDown();
            for(int i=0;i<200&&s.status(OWNER,x.assistId()).metrics().inFlight()!=0;i++)Thread.sleep(10);
            assertNull(s.status(OWNER,x.assistId()).card(),"pre-reset result must not adopt after reset");
        }finally{release.countDown();}
    }
    @Test void topicChangeClearsOldContextInRolling() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,null,null,null));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"이제 새 주제로 바꿔서 다른 이야기를 시작합니다.");waitForCalls(pipe,2);
        for(int i=0;i<200&&s.status(OWNER,x.assistId()).metrics().inFlight()!=0;i++)Thread.sleep(10);
        var history=(Map<String,Object>)s.transcriptDiagnostics(OWNER,x.assistId()).get("history");
        assertEquals(2L,((Number)history.get("contextEpoch")).longValue(),"topic change bumps the context epoch");
        time.value+=16_000;s.maintain();
        say(s,x,3,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,3);
        assertTrue(pipe.context.stream().anyMatch(t->t.contains("새 주제")),"topic-changing utterance itself becomes the new context");
        assertFalse(pipe.context.stream().anyMatch(t->t.contains("안경")||t.contains("블루투스")),"old-topic turns excluded after confirmed topic change");
    }}
    @Test void topicResetDisabledKeepsWholeBuffer() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,null,null,null,false));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");waitForCalls(pipe,1);
        time.value+=16_000;s.maintain();
        say(s,x,2,true,"이제 새 주제로 바꿔서 다른 이야기를 시작합니다.");waitForCalls(pipe,2);
        for(int i=0;i<200&&s.status(OWNER,x.assistId()).metrics().inFlight()!=0;i++)Thread.sleep(10);
        time.value+=16_000;s.maintain();
        say(s,x,3,true,"개발자 설정에서 기억 범위를 조절하는 방법을 알려줘.");waitForCalls(pipe,3);
        assertTrue(pipe.context.stream().anyMatch(t->t.contains("안경")),"topic reset off preserves the rolling buffer");
    }}
    @Test void diagnosticsExposeAppliedHistoryAndSelection() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        s.displayPrefs(owner->prefs(true,30_000L,400,200,false));
        var x=s.startPublicDisplay(OWNER);output(s,x);s.maintain();
        say(s,x,1,true,"안경 페어링과 블루투스 연결 상태를 점검하는 내용입니다.");waitForCalls(pipe,1);
        var d=s.transcriptDiagnostics(OWNER,x.assistId());var history=(Map<String,Object>)d.get("history");
        assertEquals(true,history.get("enabled"));assertEquals(30_000L,((Number)history.get("windowMs")).longValue());
        assertEquals(400,((Number)history.get("maxChars")).intValue());assertEquals(200,((Number)history.get("maxTokens")).intValue());
        assertEquals(false,history.get("topicReset"));assertEquals(1L,((Number)history.get("contextEpoch")).longValue());
        var last=(Map<String,Object>)d.get("lastContextSelection");assertNotNull(last);assertTrue(((Number)last.get("turns")).intValue()>=0);
    }}
    @Test void contextResetEndpointRunsSessionReset() throws Exception {var time=new Time();var pipe=new Pipe();try(var s=service(time,pipe)){
        var owners=org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);org.mockito.Mockito.when(owners.ownerKey()).thenReturn("synthetic-owner");
        var address=org.mockito.Mockito.mock(InterviewDemoPublicAddress.class);
        var c=new DisplayConversateController(s,owners,address,time);ReflectionTestUtils.setField(c,"phoneTestEnabled",true);
        var http=new org.springframework.mock.web.MockHttpServletRequest();http.setMethod("POST");http.setScheme("http");http.setServerName("localhost");http.setServerPort(80);http.setRemoteAddr("127.0.0.1");http.addHeader("Origin","http://localhost");http.addHeader("X-Display-Client","1");
        var v=c.phoneTest(new DisplayConversateController.Connection(null,0,CLIENT,true,false),http).getBody();
        var r=c.contextReset(new DisplayConversateController.Connection(v.assistId(),v.epoch(),CLIENT),http);
        assertEquals(200,r.getStatusCode().value());assertEquals("CONTEXT_RESET",r.getBody().reason());
    }}
}
