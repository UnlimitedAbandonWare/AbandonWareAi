package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AutoVoiceTriggerConversateTest {
    static final String OWNER=org.apache.commons.codec.digest.DigestUtils.sha256Hex("synthetic-auto-owner");
    static LensDisplayPrefs settings(boolean hints) throws Exception {
        return LensDisplayPrefs.defaults(540).patch(new ObjectMapper().readValue("""
          {"autoVoiceTrigger":{"modeEnabled":true,"hintsEnabled":%s,"phrases":[
          {"id":"short","language":"ko","text":"안녕"},{"id":"long","language":"ko","text":"안녕하세요"}]}}
          """.formatted(hints),LensDisplayPrefs.Patch.class));
    }
    @Test void finalLiteralActivationIsOneShotAndArmedDoesNotGenerate() throws Exception {
        var prefs=settings(true);var calls=new AtomicInteger();
        var pipe=new ConversateAnswerPipeline(){
            @Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String id,boolean forced,LensDisplayPrefs.AutoVoiceTrigger options){
                assertTrue(forced);assertEquals(200,options.hintChars());assertEquals(3,options.hintLines());calls.incrementAndGet();
                return new Outcome("GENERATED",new ConversateSessionService.Card("SHOW","CUE","대화를 시작하고 상대의 질문을 확인하세요.",List.of(),now+20000));
            }
        };
        try(var s=new ConversateSessionService(Clock.systemUTC(),pipe)){
            s.displayPrefs(o->prefs);var x=s.startPublicDisplay(OWNER);
            s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,true);
            assertEquals("ARMED",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            say(s,x,"one",1,false,"안녕하세요");say(s,x,"one",2,true,"질문을 설명합니다");
            assertEquals(0,calls.get());s.maintain();assertEquals(0,calls.get());
            say(s,x,"two",1,true,"그러면 안녕하세요, 시작해 볼까요?");
            for(int i=0;i<200&&s.status(OWNER,x.assistId()).card()==null;i++)Thread.sleep(5);
            assertEquals(1,calls.get(),()->s.autoVoiceState(OWNER,x.assistId())+" reason="+s.status(OWNER,x.assistId()).reason()+" metrics="+s.status(OWNER,x.assistId()).metrics());assertNotNull(s.status(OWNER,x.assistId()).card());
            assertEquals("ACTIVE",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            say(s,x,"two",1,true,"그러면 안녕하세요, 시작해 볼까요?");
            assertEquals(1,calls.get());assertEquals("long",prefs.autoVoiceTrigger().match("중간 안녕하세요 끝").id());
            s.disarmAutoVoice(OWNER,x.assistId(),x.epoch());assertNull(s.status(OWNER,x.assistId()).card());
            assertEquals("DISARMED",s.autoVoiceState(OWNER,x.assistId()).get("state"));
        }
    }
    @Test void consentHintsOffAndOtherInputCannotActivate() throws Exception {
        var prefs=settings(false);
        try(var s=new ConversateSessionService()){
            s.displayPrefs(o->prefs);var x=s.startPublicDisplay(OWNER);
            assertThrows(Exception.class,()->s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,false));
            s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,true);
            s.submit(OWNER,x.assistId(),x.epoch(),new ConversateQuestionPolicy.Utterance("text","text",1,true,"안녕"));
            assertEquals("ARMED",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            say(s,x,"voice",1,true,java.text.Normalizer.normalize("안녕하세요",java.text.Normalizer.Form.NFD));
            assertEquals("ACTIVE",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            assertEquals(0,s.status(OWNER,x.assistId()).metrics().started());assertNull(s.status(OWNER,x.assistId()).card());
        }
    }
    @Test void selectedSttLanguageRejectsUnsupportedEnginesBeforeAnyCall() throws Exception {
        var mapper=new ObjectMapper();
        for(String engine:List.of("local","auto","deepgram"))assertThrows(Exception.class,()->mapper.readValue(
            "{\"engine\":\""+engine+"\",\"language\":\"en\",\"fallbackAllowed\":false}",ConversateCloudStt.StreamPolicy.class));
        var policy=mapper.readValue("{\"engine\":\"soniox\",\"language\":\"en\",\"fallbackAllowed\":false}",ConversateCloudStt.StreamPolicy.class);
        assertEquals("en",mapper.valueToTree(policy).path("language").asText());
    }
    @Test void stopInvalidatesPendingStartupAndAutomaticContinuation() throws Exception {
        try(var s=new ConversateSessionService()){
            s.displayPrefs(o->uncheckedSettings());var x=s.startPublicDisplay(OWNER);
            s.disarmAutoVoice(OWNER,x.assistId(),x.epoch());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,true));
            var stopped=s.status(OWNER,x.assistId());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->s.armAutoVoice(OWNER,x.assistId(),stopped.epoch(),true,true));
            assertEquals("DISARMED",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            assertEquals(0L,s.autoVoiceState(OWNER,x.assistId()).get("activationValidUntil"));
        }
    }
    @Test void hintsOffFramesRenewOnlyActivationAndOldRevisionCannotReenable() throws Exception {
        var time=new ConversateRollingTranscriptTest.Time();try(var s=new ConversateSessionService(time)){
            s.displayPrefs(o->uncheckedSettings());var x=s.startPublicDisplay(OWNER);s.registerCapture(OWNER,x.assistId(),x.epoch(),()->{});s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,true);
            say(s,x,"greeting",1,true,"안녕하세요");s.autoVoiceFrame(OWNER,x.assistId(),x.epoch(),1,true);
            time.value+=4000;s.autoVoiceFrame(OWNER,x.assistId(),x.epoch(),2,false);s.autoVoiceFrame(OWNER,x.assistId(),x.epoch(),1,true);
            assertFalse(s.hintsEnabled(OWNER,x.assistId()));assertEquals(0L,s.autoVoiceState(OWNER,x.assistId()).get("hintDisplayValidUntil"));
            time.value+=4000;s.maintain();assertEquals("ACTIVE",s.autoVoiceState(OWNER,x.assistId()).get("state"));
            time.value+=1001;s.maintain();assertEquals("DISARMED",s.autoVoiceState(OWNER,x.assistId()).get("state"));
        }
    }
    @Test void completeSentenceBudgetCountsCodepointsAndDoesNotSplitEmoji(){
        assertEquals("Hi 😀.",ConversateSessionService.atomicHintText("Hi 😀. A sentence too long to fit.",6,"en"));
        assertEquals("",ConversateSessionService.atomicHintText("A sentence too long to fit.",6,"en"));
    }
    @Test void oversizedFirstSentencePublishesACompleteNoticeWithoutAnotherProviderCall() throws Exception {
        for(String language:List.of("ko","en")){
            var prefs=LensDisplayPrefs.defaults(540).patch(new ObjectMapper().readValue("""
                {"autoVoiceTrigger":{"modeEnabled":true,"language":"%s","hintChars":200,
                "phrases":[{"id":"hello","language":"%s","text":"hello"}]}}
                """.formatted(language,language),LensDisplayPrefs.Patch.class));
            var calls=new AtomicInteger();var pipe=new ConversateAnswerPipeline(){
                @Override public Outcome answerPublicDisplay(String q,List<String> c,long now,String id,boolean forced,LensDisplayPrefs.AutoVoiceTrigger options){
                    calls.incrementAndGet();return new Outcome("GENERATED",new ConversateSessionService.Card("SHOW","CUE","a".repeat(200)+".",List.of("discarded-source"),now+20000));
                }
            };
            try(var s=new ConversateSessionService(Clock.systemUTC(),pipe)){
                s.displayPrefs(o->prefs);var x=s.startPublicDisplay(OWNER);s.armAutoVoice(OWNER,x.assistId(),x.epoch(),false,true);
                say(s,x,"oversized",1,true,"hello");
                for(int i=0;i<200&&s.status(OWNER,x.assistId()).metrics().samples()==0;i++)Thread.sleep(5);
                var result=s.status(OWNER,x.assistId());assertNotNull(result.card(),"no-fit result must publish a complete notice");
                assertEquals(language.equals("en")?"Hint is too long.":"힌트가 너무 길어요.",result.card().text());
                assertEquals("HINT_BUDGET_EXCEEDED",result.reason());assertTrue(result.card().sourceIds().isEmpty());
                assertTrue(result.card().text().codePointCount(0,result.card().text().length())<=200);assertEquals(1,calls.get());
            }
        }
    }
    static LensDisplayPrefs uncheckedSettings(){try{return settings(true);}catch(Exception invalid){throw new AssertionError(invalid);}}
    @Test void delayedProviderStartupCannotOverwriteExplicitStop() throws Exception {
        var owners=org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);org.mockito.Mockito.when(owners.ownerKey()).thenReturn("synthetic-auto-owner");
        var asr=org.mockito.Mockito.mock(ConversateAsrBridge.class);org.mockito.Mockito.when(asr.available()).thenReturn(true);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(asr.start(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return Map.of();});
        try(var s=new ConversateSessionService()){
            var c=new DisplayConversateController(s,owners,new InterviewDemoPublicAddress());
            org.springframework.test.util.ReflectionTestUtils.setField(c,"asr",asr);org.springframework.test.util.ReflectionTestUtils.setField(c,"audioEnabled",true);org.springframework.test.util.ReflectionTestUtils.setField(c,"phoneTestEnabled",true);
            var http=new org.springframework.mock.web.MockHttpServletRequest();http.setMethod("POST");http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");String client="b".repeat(32);
            var initial=c.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            var p=new ObjectMapper().readValue("{\"autoVoiceTrigger\":{\"modeEnabled\":true,\"phrases\":[{\"id\":\"hi\",\"language\":\"ko\",\"text\":\"안녕\"}]}}",LensDisplayPrefs.Patch.class);
            var ready=c.lensSettings(new DisplayConversateController.LensSettings(initial.assistId(),initial.epoch(),client,p,false,0L),http).getBody();
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var started=executor.submit(()->c.audioStart(new DisplayConversateController.Connection(ready.assistId(),ready.epoch(),client,false,false,null,true),http));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var stopped=c.audioStop(new DisplayConversateController.AudioStop(ready.assistId(),ready.epoch(),client,false,true),http).getBody();release.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,()->started.get(5,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("DISARMED",stopped.autoVoiceRuntime().get("state"));
                assertEquals("DISARMED",c.poll(new DisplayConversateController.Connection(stopped.assistId(),stopped.epoch(),client),http).getBody().autoVoiceRuntime().get("state"));
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test void chosenLanguageReachesExistingSonioxAdapter() throws Exception {
        var service=org.mockito.Mockito.mock(com.example.lms.service.stt.SonioxSttService.class);org.mockito.Mockito.when(service.isConfigured()).thenReturn(true);org.mockito.Mockito.when(service.transcribePcm16Mono(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("en"))).thenReturn(reactor.core.publisher.Flux.never());
        var transport=new SonioxAsrTransport(new ObjectMapper(),service,e->{},e->{},"en");transport.close().join();
        org.mockito.Mockito.verify(service).transcribePcm16Mono(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("en"));
        org.mockito.Mockito.verify(service,org.mockito.Mockito.never()).transcribePcm16Mono(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test void publicOptionsReachPromptWithoutPrivateRetrieval() throws Exception {
        var options=LensDisplayPrefs.defaults(540).patch(new ObjectMapper().readValue("{\"autoVoiceTrigger\":{\"modeEnabled\":true,\"language\":\"en\",\"hintLines\":9,\"hintChars\":500,\"preset\":\"interview\",\"phrases\":[{\"id\":\"hello\",\"language\":\"en\",\"text\":\"Hello\"}]}}",LensDisplayPrefs.Patch.class)).autoVoiceTrigger();
        var cues=org.mockito.Mockito.mock(ConversateApiCueService.class);var privateRag=org.mockito.Mockito.mock(com.example.lms.service.ChatService.class);
        var pipe=new ConversateAnswerPipeline();pipe.apiCues(cues);pipe.sharedRag(privateRag);
        pipe.answerPublicDisplay("Hello",List.of(),1000,"request",true,options);
        org.mockito.Mockito.verify(cues).answer("Hello",List.of(),List.of(),true,true,500,null,options);org.mockito.Mockito.verifyNoInteractions(privateRag);
        var prompt=ConversateCardPrompt.cueHint("Hello",List.of(),List.of(),false,500,options);
        String text=((dev.langchain4j.data.message.SystemMessage)prompt.messages().get(0)).text();assertTrue(text.contains("English"));assertTrue(text.contains("500"));assertTrue(text.contains("9 visual lines"));assertFalse(text.contains("Z_FORCE"));
    }
    static void say(ConversateSessionService s,ConversateSessionService.Snapshot x,String id,int revision,boolean fin,String text){
        s.submit(OWNER,x.assistId(),x.epoch(),new ConversateQuestionPolicy.Utterance(id,id,revision,fin,text),"phone_voice",null);
    }
}
