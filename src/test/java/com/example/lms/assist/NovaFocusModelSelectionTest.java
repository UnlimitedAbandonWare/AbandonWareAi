package com.example.lms.assist;

import com.example.lms.api.*;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusModelSelectionTest {
    static final ObjectMapper JSON=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    static NovaFocusSettings settings(String model,boolean snapshot) throws Exception {
        var tree=JSON.valueToTree(NovaFocusSettings.defaults());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("enabled",true);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set("answerSelection",JSON.readTree(
            "{\"mode\":\"FIXED\",\"modelId\":\""+model+"\"}"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set("snapshot",JSON.readTree(
            "{\"enabled\":"+snapshot+",\"source\":\"FOLD_REAR\"}"));
        return JSON.treeToValue(tree,NovaFocusSettings.class);
    }
    @Test void fixedSelectionSurvivesSettingsSerialization() throws Exception {
        var selected=settings("fixture-model-a",false);
        assertEquals("fixture-model-a",JSON.valueToTree(selected).path("answerSelection").path("modelId").asText());
    }
    @Test void acceptedQuestionCarriesExactSelectedModelToChatDto() throws Exception {
        try(var f=new Fixture(settings("fixture-model-a",false))){
            f.ask();f.time.now=1200;f.focus.maintain();
            var request=f.completed();
            assertEquals("fixture-model-a",request.getModel());
            assertTrue(request.isStrictModelSelection(),"fixed choice must reach existing strict catalog/router");
        }
    }
    @Test void settingsChangeDuringPhotoWaitCannotChangeAcceptedQuestionModel() throws Exception {
        try(var f=new Fixture(settings("fixture-model-a",true))){
            f.ask();f.time.now=1200;f.focus.maintain();
            assertEquals("SNAPSHOT",f.focus.view(f.owner,f.id,1).phase());
            var changed=settings("fixture-model-b",true);
            when(f.history.settings(f.owner,"live",1,changed)).thenReturn(new NovaFocusHistoryService.Settings(2,changed));
            f.focus.configure(f.owner,f.id,1,1,changed);
            f.time.now=16201;f.focus.maintain();
            assertEquals("fixture-model-a",f.completed().getModel(),"photo wait must retain question-time model");
        }
    }
    static final class Time extends Clock {
        volatile long now;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    static final class Fixture implements AutoCloseable {
        final String owner="a".repeat(64),id="selection-fixture";
        final Time time=new Time();
        final NovaFocusHistoryService history=mock(NovaFocusHistoryService.class);
        final NovaFocusService focus;
        final ChatRunRegistry runs=new ChatRunRegistry();
        final List<ChatRequestDto> requests=new CopyOnWriteArrayList<>();
        Fixture(NovaFocusSettings settings){
            when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(1,settings));
            when(history.open(anyString(),anyString())).thenReturn(7L);
            var sequence=new AtomicInteger();
            when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(c->
                new NovaFocusHistoryService.Accepted("turn-"+sequence.incrementAndGet(),7L,"ACCEPTED",true));
            when(history.terminal(anyString(),anyString(),anyString(),eq("COMPLETED"),anyString())).thenReturn(true);
            var chat=mock(ChatService.class);
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(c->{requests.add(c.getArgument(0));return ChatResult.of("합성 응답","fixture-model-a",false);});
            ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
            @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(adapter);
            focus=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time);
            focus.attach(owner,"live",id,1);
        }
        void ask(){focus.open(owner,id,1,"fold");focus.input(owner,id,1,UUID.randomUUID().toString(),"합성 모델 선택 질문");}
        ChatRequestDto completed() throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<deadline){
                if(!requests.isEmpty()&&"ANSWER_READY".equals(focus.view(owner,id,1).phase())
                    &&!Boolean.TRUE.equals(focus.diagnostics(owner,id,1).get("busy")))return requests.get(0);
                Thread.sleep(5);
            }
            throw new AssertionError("focus answer did not complete");
        }
        public void close(){focus.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
}

