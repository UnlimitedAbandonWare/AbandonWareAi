package com.example.lms.assist;

import com.example.lms.api.*;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusRecentTranscriptTest {
    @Test void disabledRecentContextClearsStoredFinalsAndDoesNotRetainNewOnes() throws Exception {
        try(var f=new Fixture()){
            f.audio("before",0,true,"꺼지기 전 합성 표식");
            f.configureRecent(false,180,12,2000,false);
            f.audio("off",0,true,"꺼진 동안 합성 표식");
            assertTrue(f.askTyped("문맥 없이 질문").transcript().isEmpty());
            f.presented();
            f.configureRecent(true,180,12,2000,false);
            assertTrue(f.askTyped("다시 켠 뒤 질문").transcript().isEmpty(),"enabling cannot revive deleted input");
        }
    }
    @Test void configuredRecentLimitsApplyWithoutChangingAudioContextLifetime() throws Exception {
        try(var f=new Fixture()){
            f.configureRecent(true,30,1,512,false);
            f.audio("old",0,true,"이전 합성 표식");
            f.audio("new",0,true,"최신 합성 표식 "+"긴말".repeat(800));
            f.sessions.audioMetrics(f.owner,f.id,f.epoch,new ConversateSessionService.AudioMetrics(0,0,1,0,0,"WAITING"));
            f.epoch=f.sessions.nextSegment(f.owner,f.id,f.epoch).epoch();
            f.focus.attach(f.owner,"live",f.id,f.epoch);
            var context=f.askTyped("최신 문맥 질문");
            assertEquals(1,context.transcript().size());
            assertTrue(context.transcript().get(0).text().startsWith("최신 합성 표식"));
            assertTrue(ChatConversationContext.transcriptTokens(context.transcript())<=512);
            f.presented();
            f.time.now+=30001;
            assertTrue(f.askTyped("만료 뒤 질문").transcript().isEmpty());
        }
    }
    @Test void photoWaitKeepsAcceptedContextEvenWhenCollectionSettingsChange() throws Exception {
        try(var f=new Fixture()){
            f.configureRecent(true,180,12,2000,true);
            f.audio("before-photo",0,true,"확정 시점 합성 표식");
            f.focus.open(f.owner,f.id,f.epoch,"fold");
            f.focus.input(f.owner,f.id,f.epoch,UUID.randomUUID().toString(),"사진 질문");
            f.time.now+=1200;f.focus.maintain();
            var command=f.focus.snapshotCommand(f.owner,f.id,f.epoch,f.time.now);
            assertNotNull(command);
            f.configureRecent(false,180,12,2000,true);
            f.audio("after-photo",0,true,"사진 대기 중 뒤늦은 표식");
            f.focus.snapshotResult(f.owner,f.id,f.epoch,command.requestId(),command.captureId(),null,null,"synthetic_unavailable");
            var context=f.complete();
            assertEquals(1,context.transcript().size());
            assertTrue(context.memoryText().contains("확정 시점 합성 표식"));
            assertFalse(context.memoryText().contains("뒤늦은 표식"));
        }
    }
    @Test void oversizedEscapedFinalKeepsBoundedNewestContent() throws Exception {
        try(var f=new Fixture()){
            f.audio("large",0,true,"최신 표식 "+"\\\"".repeat(2100));
            var context=f.askTyped("최근 문맥 질문");
            assertEquals(1,context.transcript().size(),"large latest final must be clipped, not entirely lost");
            assertTrue(context.transcript().get(0).text().startsWith("최신 표식"));
            assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(context.transcript()).length<=2000);
            assertFalse(context.toString().contains("최신 표식"));
        }
    }
    @Test void preWakeFinalSurvivesHintClearingAndAudioRolloverIntoPrompt() throws Exception {
        try(var f=new Fixture()){
            f.audio("place",0,true,"합성 약속 장소는 해솔 공원입니다.");
            f.audio("partial",0,false,"미확정 장소는 바다입니다.");
            f.sessions.audioMetrics(f.owner,f.id,1,new ConversateSessionService.AudioMetrics(0,0,1,0,0,"WAITING"));
            f.epoch=f.sessions.nextSegment(f.owner,f.id,1).epoch();
            f.focus.attach(f.owner,"live",f.id,f.epoch);
            assertFalse(f.focus.audio(f.owner,f.id,1,new ConversateQuestionPolicy.Utterance("late","late",0,true,"오래된 입력")));
            assertFalse(f.focus.audio("b".repeat(64),f.id,f.epoch,new ConversateQuestionPolicy.Utterance("foreign","foreign",0,true,"다른 소유자")));
            var context=f.askVoice("question","노바 방금 말한 장소 이름은?");
            assertTrue(context.memoryText().contains("해솔 공원"),"pre-wake final must reach answer prompt");
            assertFalse(context.memoryText().contains("미확정 장소"));
            assertFalse(context.memoryText().contains("오래된 입력"));
            assertFalse(context.memoryText().contains("다른 소유자"));
            assertFalse(context.memoryText().contains("방금 말한 장소 이름은?"),"current question occurs only in current-user message");
            assertFalse(f.requests.get(0).isUseWebSearch());
            assertEquals("방금 말한 장소 이름은?",f.requests.get(0).getMessage());
            assertTrue(context.memoryText().contains("UNKNOWN"));
        }
    }
    @Test void finalRevisionReplacesSpanAndResetOrExplicitCloseClearsRecentLane() throws Exception {
        try(var f=new Fixture()){
            f.audio("place",0,true,"옛장소 표식");
            f.audio("place",1,true,"새장소 표식");
            f.audio("place",0,true,"늦은 구버전");
            var first=f.askTyped("첫 질문");
            assertTrue(first.memoryText().contains("새장소 표식"));
            assertFalse(first.memoryText().contains("옛장소 표식"));
            assertFalse(first.memoryText().contains("늦은 구버전"));
            f.sessions.control(f.owner,f.id,f.epoch,"context_reset");
            var reset=f.askTyped("초기화 뒤 질문");
            assertFalse(reset.memoryText().contains("새장소 표식"));
            f.focus.close(f.owner,f.id,f.epoch,"user_closed");
            f.audio("next",0,true,"다음 맥락 표식");
            assertTrue(f.askTyped("다음 질문").memoryText().contains("다음 맥락 표식"));
            f.focus.close(f.owner,f.id,f.epoch,"user_closed");
            assertFalse(f.askTyped("종료 뒤 질문").memoryText().contains("다음 맥락 표식"));
        }
    }
    @Test void recentLaneExpiresAndRetainsAtMostTwelveFinalSpans() throws Exception {
        try(var f=new Fixture()){
            f.audio("expired",0,true,"만료될 표식");
            f.time.now+=180001;
            for(int i=0;i<13;i++){f.audio("span-"+i,0,true,"최근표식"+i+"끝");f.time.now++;}
            var context=f.askTyped("최근 문맥 질문");
            assertTrue(context.memoryText().contains("최근표식12끝"));
            assertFalse(context.memoryText().contains("최근표식0끝"));
            assertFalse(context.memoryText().contains("만료될 표식"));
        }
    }
    static final class Time extends Clock {
        volatile long now;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    static final class Fixture implements AutoCloseable {
        final String owner="a".repeat(64),id;
        long epoch=1;
        final Time time=new Time();
        final ConversateSessionService sessions=new ConversateSessionService();
        final NovaFocusService focus;
        final ChatRunRegistry runs=new ChatRunRegistry();
        final NovaFocusHistoryService history=mock(NovaFocusHistoryService.class);
        long settingsVersion=1;
        final List<ChatConversationContext> contexts=new CopyOnWriteArrayList<>();
        final List<ChatRequestDto> requests=new CopyOnWriteArrayList<>();
        Fixture(){
            var d=NovaFocusSettings.defaults();
            var enabled=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation());
            when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(1,enabled));
            when(history.settings(anyString(),anyString(),anyLong(),any())).thenAnswer(c->
                new NovaFocusHistoryService.Settings((Long)c.getArgument(2)+1,c.getArgument(3)));
            when(history.open(anyString(),anyString())).thenReturn(7L);
            var sequence=new AtomicInteger();
            when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(c->
                new NovaFocusHistoryService.Accepted("turn-"+sequence.incrementAndGet(),7L,"ACCEPTED",true));
            when(history.terminal(anyString(),anyString(),anyString(),eq("COMPLETED"),anyString())).thenReturn(true);
            var chat=mock(ChatService.class);
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(c->{requests.add(c.getArgument(0));contexts.add(c.getArgument(2));return ChatResult.of("합성 응답","recording",false);});
            ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
            @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(adapter);
            focus=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time);
            ReflectionTestUtils.setField(sessions,"novaFocus",focus);
            var session=sessions.startPublicDisplay(owner);id=session.assistId();epoch=session.epoch();
            focus.attach(owner,"live",id,epoch);sessions.control(owner,id,epoch,"hints_off");
        }
        void configureRecent(boolean enabled,int age,int count,int budget,boolean snapshot) throws Exception {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(NovaFocusSettings.defaults());
            node.put("enabled",true);
            node.set("recentContext",mapper.readTree("{\"enabled\":"+enabled+",\"maxAgeSeconds\":"+age+",\"maxUtterances\":"+count+",\"tokenBudget\":"+budget+"}"));
            node.set("snapshot",mapper.readTree("{\"enabled\":"+snapshot+",\"source\":\"FOLD_REAR\"}"));
            var value=mapper.treeToValue(node,NovaFocusSettings.class);
            settingsVersion=focus.configure(owner,id,epoch,settingsVersion,value).settingsVersion();
        }
        void audio(String source,int revision,boolean fin,String text){
            sessions.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance(source,source,revision,fin,text),"phone_voice","fixture");
        }
        ChatConversationContext askVoice(String source,String question) throws Exception {
            audio(source,0,true,question);return complete();
        }
        ChatConversationContext askTyped(String question) throws Exception {
            focus.open(owner,id,epoch,"fold");focus.input(owner,id,epoch,UUID.randomUUID().toString(),question);return complete();
        }
        void presented(){
            var v=focus.view(owner,id,epoch);
            assertTrue(focus.rendered(new NovaFocusService.Receipt(v.serverInstanceId(),v.activationId(),v.turnId(),v.answerVersion(),v.renderReceiptTicket(),"first_visible")));
            assertTrue(focus.rendered(new NovaFocusService.Receipt(v.serverInstanceId(),v.activationId(),v.turnId(),v.answerVersion(),v.renderReceiptTicket(),"presentation_done")));
        }
        ChatConversationContext complete() throws Exception {
            int before=contexts.size();time.now+=1200;focus.maintain();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<deadline){
                if(contexts.size()>before&&"ANSWER_READY".equals(focus.view(owner,id,epoch).phase())
                    &&!Boolean.TRUE.equals(focus.diagnostics(owner,id,epoch).get("busy")))return contexts.get(before);
                Thread.sleep(5);
            }
            throw new AssertionError("focus answer did not complete");
        }
        public void close(){sessions.close();focus.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
}
