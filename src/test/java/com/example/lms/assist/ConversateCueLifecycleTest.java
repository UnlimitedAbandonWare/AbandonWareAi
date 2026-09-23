package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateCueLifecycleTest {
    static class MutableClock extends Clock {long now=100000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}public long millis(){return now;}}
    static class Pipeline extends ConversateAnswerPipeline {
        final List<List<String>> contexts=new CopyOnWriteArrayList<>();final AtomicInteger calls=new AtomicInteger();volatile boolean noCue,topicChanged;
        @Override boolean usesApiCues(){return true;}
        @Override public Outcome answerLive(String q,List<String> context,List<PreparedMaterialReader.Material> docs,long now,String path,String id,boolean forceHint){
            contexts.add(context);calls.incrementAndGet();var card=noCue?null:new ConversateSessionService.Card("SHOW","CUE","조건을 확인하세요.",List.of(),now+15000);
            return new Outcome(noCue?"NO_CUE":"API_CUE",card).observed(new Stages(null,0,null,null,null,0L,null,null,Map.of("topicChanged",topicChanged,"cueDecision",noCue?"NO_CUE":"CUE")));
        }
    }
    static ConversateQuestionPolicy.Utterance utterance(String id,String text){return new ConversateQuestionPolicy.Utterance(id,id,1,true,text);}
    static void settle(ConversateSessionService service,ConversateSessionService.Snapshot s)throws Exception{for(int i=0;i<100;i++){if(service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).metrics().inFlight()==0)return;Thread.sleep(10);}fail("worker did not settle");}
    @Test void displayedTranscriptAndHintExpireTogetherButContextSurvivesForFollowUp()throws Exception{
        var clock=new MutableClock();var pipeline=new Pipeline();try(var service=new ConversateSessionService(clock,pipeline)){
            var s=service.start("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");var output=service.output("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch()).subscribe();try{
                service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("u1","첫 질문"),"phone_voice","r1");settle(service,s);
                var shown=service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId());assertNotNull(shown.card());assertEquals(clock.now+15000,shown.card().expiresAt());assertEquals(shown.card().expiresAt(),shown.caption().expiresAt());
                clock.now+=15001;service.maintain();var expired=service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId());assertNull(expired.card());assertNull(expired.caption());assertEquals(2,expired.metrics().contextTurns());
                service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("u2","그러면요?"),"phone_voice","r2");settle(service,s);
                assertTrue(pipeline.contexts.get(1).stream().anyMatch(t->t.contains("첫 질문")));assertTrue(pipeline.contexts.get(1).stream().anyMatch(t->t.contains("조건을 확인")));
                clock.now+=120001;service.maintain();assertEquals(0,service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).metrics().contextTurns());
            }finally{output.dispose();}
        }
    }
    @Test void noCueStillUpdatesContextDuplicatesDoNotCallAgainAndWindowIsBounded()throws Exception{
        var clock=new MutableClock();var pipeline=new Pipeline();pipeline.noCue=true;try(var service=new ConversateSessionService(clock,pipeline)){
            var s=service.start("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");var output=service.output("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch()).subscribe();try{
                for(int i=0;i<14;i++){service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("u"+i,"정보 "+i),"phone_voice","r"+i);settle(service,s);}
                assertEquals(14,pipeline.calls.get());assertEquals(12,service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).metrics().contextTurns());assertNull(service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).card());
                service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("u7","정보 7"),"phone_voice","r7");settle(service,s);assertEquals(14,pipeline.calls.get());
                assertTrue(pipeline.contexts.get(13).contains("정보 12"));assertTrue(pipeline.contexts.get(13).contains("정보 1"));assertFalse(pipeline.contexts.get(13).contains("정보 0"));
            }finally{output.dispose();}
        }
    }
    @Test void topicResetAndHintsOffKeepOnlyAppropriateVolatileContext()throws Exception{
        var clock=new MutableClock();var pipeline=new Pipeline();pipeline.topicChanged=true;try(var service=new ConversateSessionService(clock,pipeline)){
            var s=service.start("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");var output=service.output("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch()).subscribe();try{
                service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("a","첫 주제"),"phone_voice","a");settle(service,s);
                service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("b","새 주제"),"phone_voice","b");settle(service,s);assertEquals(2,service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).metrics().contextTurns());
                service.control("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),"hints_off");service.submit("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId(),s.epoch(),utterance("c","추가 맥락"),"phone_voice","c");
                assertEquals(2,pipeline.calls.get());assertEquals(3,service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).metrics().contextTurns());assertNull(service.status("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",s.assistId()).card());
            }finally{output.dispose();}
        }
    }
    @Test void repeatedTextWithDifferentUtteranceIdentityIsContextuallyReevaluated(){
        var p=new ConversateQuestionPolicy();assertEquals("CUE_PENDING",p.acceptForCue(utterance("a","그럼요?")).kind());
        assertEquals("CUE_PENDING",p.acceptForCue(utterance("b","그럼요?")).kind());assertEquals("DUPLICATE",p.acceptForCue(utterance("b","그럼요?")).kind());
    }
    @Test void apiTranscriptionStartsWithGatedHintsAndDoesNotDependOnLocalModelReadiness(){
        var clock=new MutableClock();try(var service=new ConversateSessionService(clock,new Pipeline())){
            var owners=org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            org.mockito.Mockito.when(owners.ownerKey()).thenReturn("synthetic-owner");
            var address=org.mockito.Mockito.mock(InterviewDemoPublicAddress.class);
            var controller=new DisplayConversateController(service,owners,address,clock);
            var local=org.mockito.Mockito.mock(com.example.lms.config.LocalLlmProcessManager.class);
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",local);
            var http=new org.springframework.mock.web.MockHttpServletRequest();http.setScheme("http");http.setServerName("localhost");http.setServerPort(80);
            http.addHeader("Origin","http://localhost");http.addHeader("X-Display-Client","1");
            var connection=new DisplayConversateController.Connection(null,0,"a".repeat(32));
            var view=controller.transcription(connection,http).getBody();assertTrue(view.hintsEnabled());assertTrue(view.ready());
            assertTrue(controller.bootstrap(connection,http).getBody().ready());org.mockito.Mockito.verifyNoInteractions(local);
            controller.hints(new DisplayConversateController.HintControl(view.assistId(),view.epoch(),"a".repeat(32),false),http);
            assertFalse(controller.transcription(connection,http).getBody().hintsEnabled());
        }
    }
}
