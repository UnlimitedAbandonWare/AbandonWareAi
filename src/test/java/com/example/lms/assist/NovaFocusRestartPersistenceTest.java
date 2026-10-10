package com.example.lms.assist;

import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusRestartPersistenceTest {
    @TempDir Path temporary;
    record Database(LocalContainerEntityManagerFactoryBean factory,NovaFocusHistoryService history) implements AutoCloseable {
        public void close(){factory.destroy();}
    }
    Database open(String url){
        var source=new DriverManagerDataSource(url,"sa","");
        var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);
        factory.setPackagesToScan("com.example.lms");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","update","hibernate.show_sql","false"));
        factory.afterPropertiesSet();
        var existingHistory=mock(ChatHistoryService.class);when(existingHistory.getRollingSummary(anyLong())).thenReturn(Optional.empty());
        var store=new NovaFocusHistoryService(new JpaTransactionManager(factory.getObject()),new ObjectMapper(),existingHistory);
        ReflectionTestUtils.setField(store,"em",SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        return new Database(factory,store);
    }
    DisplayConversateController display(NovaFocusHistoryService history,ConversateSessionService sessions,com.example.lms.web.ClientOwnerKeyResolver owners){
        var controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("focusHistory",history);
        var injection=new org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor();
        injection.setAutowiredAnnotationType(org.springframework.beans.factory.annotation.Autowired.class);injection.setBeanFactory(beans);injection.processInjection(controller);
        ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);return controller;
    }
    org.springframework.mock.web.MockHttpServletRequest displayRequest(String channel){
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setScheme("https");request.setServerName("example.test");request.setServerPort(443);
        request.addHeader("Origin","https://example.test");request.addHeader("X-Display-Client","1");request.addHeader("X-Display-Test-Channel",channel);return request;
    }
    @Test void lensFontAndRevisionSurviveDatabaseReopenWithoutCrossingFocusOwnerOrChannel() throws Exception {
        String url="jdbc:h2:file:"+temporary.resolve("lens").toAbsolutePath().toString().replace('\\','/')+";MODE=MariaDB;DATABASE_TO_UPPER=false";
        String channel="test-1234567812345678",client="12345678123442348234123456789abc";
        var owners=mock(com.example.lms.web.ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-lens-owner");
        var mapper=new ObjectMapper();String focusOwner=org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-lens-owner:"+channel);
        try(var first=open(url);var sessions=new ConversateSessionService()){
            var focusJson=mapper.valueToTree(NovaFocusSettings.defaults());((com.fasterxml.jackson.databind.node.ObjectNode)focusJson).put("answerLengthChars",480);
            first.history().settings(focusOwner,channel,0,mapper.treeToValue(focusJson,NovaFocusSettings.class));
            var controller=display(first.history(),sessions,owners);var http=displayRequest(channel);
            var view=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            var json=mapper.valueToTree(Map.of("assistId",view.assistId(),"epoch",view.epoch(),"clientId",client,"display",Map.of("hintFontPx",36),"expectedSettingsVersion",0));
            var saved=controller.lensSettings(mapper.treeToValue(json,DisplayConversateController.LensSettings.class),http).getBody();
            assertEquals(36,((Map<?,?>)saved.testStatus().get("lensDisplay")).get("hintFontPx"));
        }
        try(var second=open(url);var sessions=new ConversateSessionService()){
            var controller=display(second.history(),sessions,owners);var http=displayRequest(channel);
            var view=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            assertEquals(36,((Map<?,?>)view.testStatus().get("lensDisplay")).get("hintFontPx"));
            assertEquals(1L,((Number)view.testStatus().get("lensSettingsVersion")).longValue());
            assertEquals(480,second.history().settings(focusOwner,channel).settings().effectiveAnswerLengthChars());
            assertEquals(1L,second.history().settings(focusOwner,channel).settingsVersion());
            var reset=mapper.valueToTree(Map.of("assistId",view.assistId(),"epoch",view.epoch(),"clientId",client,"restoreDefaults",true,"expectedSettingsVersion",1));
            controller.lensSettings(mapper.treeToValue(reset,DisplayConversateController.LensSettings.class),http);
            var otherChannel=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),displayRequest("test-8765432187654321")).getBody();
            assertEquals(26,((Map<?,?>)otherChannel.testStatus().get("lensDisplay")).get("hintFontPx"));
            when(owners.ownerKey()).thenReturn("other-lens-owner");
            var otherOwner=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            assertEquals(26,((Map<?,?>)otherOwner.testStatus().get("lensDisplay")).get("hintFontPx"));
            when(owners.ownerKey()).thenReturn("synthetic-lens-owner");
        }
        try(var third=open(url);var sessions=new ConversateSessionService()){
            var controller=display(third.history(),sessions,owners);
            var view=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),displayRequest(channel)).getBody();
            assertEquals(26,((Map<?,?>)view.testStatus().get("lensDisplay")).get("hintFontPx"));
            assertEquals(2L,((Number)view.testStatus().get("lensSettingsVersion")).longValue());
            assertEquals(1L,third.history().settings(focusOwner,channel).settingsVersion());
        }
    }
    @Test void cameraWakeAndCustomInstructionSurviveReopenAndFreshFocusAttach() throws Exception {
        var mapper=new ObjectMapper();String owner="camera-profile-owner",channel="camera-profile-channel";
        for(boolean cameraAllowed:List.of(false,true)){
            String url="jdbc:h2:file:"+temporary.resolve("camera-"+cameraAllowed).toAbsolutePath().toString().replace('\\','/')+";MODE=MariaDB;DATABASE_TO_UPPER=false";
            NovaFocusSettings chosen;
            try(var first=open(url)){
                var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(NovaFocusSettings.defaults());
                node.put("cameraWakeWord","사진봇");node.put("answerInstruction","지시 A: 짧은 존댓말로 답한다.");node.put("answerPreset","CUSTOM");
                ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("snapshot")).put("cameraAllowed",cameraAllowed);
                chosen=mapper.treeToValue(node,NovaFocusSettings.class);
                var saved=first.history().settings(owner,channel,0,chosen);
                chosen=saved.settings(); // The persistence boundary resolves optional defaults before storage.
                assertEquals(1,saved.settingsVersion());assertEquals(chosen,first.history().settings(owner,channel).settings());
                assertThrows(IllegalArgumentException.class,()->first.history().settings(owner,channel,0,saved.settings()));
            }
            try(var reopened=open(url);var service=new NovaFocusService(reopened.history(),mock(org.springframework.beans.factory.ObjectProvider.class),mock(com.example.lms.api.PublicChatAdmissionGuard.class))){
                service.attach(owner,channel,"fresh-camera-assist",1);
                var profile=service.settings(owner,"fresh-camera-assist",1);
                assertEquals(1,profile.settingsVersion());assertEquals(chosen,profile.settings());
                assertEquals("사진봇",profile.settings().cameraWakeWordOrDefault());
                assertEquals("지시 A: 짧은 존댓말로 답한다.",profile.settings().effectiveAnswerInstruction());
                assertEquals(NovaFocusSettings.AnswerPreset.CUSTOM,profile.settings().effectiveAnswerPreset());
                assertEquals(cameraAllowed,profile.settings().snapshot().cameraAllowed());
                for(String[] other:List.of(new String[]{"other-camera-owner",channel},new String[]{owner,"other-camera-channel"})){
                    service.attach(other[0],other[1],other[0]+other[1],1);
                    var defaults=service.settings(other[0],other[0]+other[1],1);
                    assertEquals(0,defaults.settingsVersion());
                    assertEquals(NovaFocusSettings.defaults().cameraWakeWordOrDefault(),defaults.settings().cameraWakeWordOrDefault());
                    assertEquals("",defaults.settings().effectiveAnswerInstruction());
                    assertEquals(NovaFocusSettings.AnswerPreset.GENERAL,defaults.settings().effectiveAnswerPreset());
                    assertEquals(NovaFocusSettings.defaults().snapshot(),defaults.settings().snapshot());
                }
            }
        }
    }
    @Test void completedHistorySettingsAndRequestIdentitySurviveDatabaseReopen(){
        String url="jdbc:h2:file:"+temporary.resolve("nova").toAbsolutePath().toString().replace('\\','/')+";MODE=MariaDB;DATABASE_TO_UPPER=false";
        String owner="synthetic-owner";Long room;String turn;
        try(var first=open(url)){
            var store=first.history();store.settings(owner,"live",0,NovaFocusSettings.defaults());room=store.open(owner,"live");
            var accepted=store.accept(owner,"live","activation-one",NovaFocusState.typedRequestId("request-one"),"이름은 무엇인가요?");turn=accepted.turnId();
            assertTrue(store.terminal(owner,"live",turn,"COMPLETED","별빛입니다."));
            store.accept(owner,"live","activation-one","pending","완료 여부가 불명확한 질문");
        }
        try(var second=open(url)){
            var store=second.history();store.recover(owner,"live");assertEquals(room,store.open(owner,"live"));
            assertEquals(1,store.settings(owner,"live").settingsVersion());
            var page=store.page(owner,"live",null,10);assertEquals(2,page.turns().size());
            assertEquals("OUTCOME_UNKNOWN",page.turns().get(0).state());assertEquals("",page.turns().get(1).answer());
            String key=NovaFocusState.typedRequestId("request-one");
            assertTrue(store.knownRequest(owner,"live",key,"이름은 무엇인가요?"));
            assertFalse(store.knownRequest("other-owner","live",key,"이름은 무엇인가요?"));
            assertFalse(store.knownRequest(owner,"live",NovaFocusState.typedRequestId("new-request"),"이름은 무엇인가요?"));
            var conflict=assertThrows(IllegalArgumentException.class,()->store.knownRequest(owner,"live",key,"다른 질문"));
            assertEquals("focus_request_conflict",conflict.getMessage());
            var replay=store.accept(owner,"live","activation-two",key,"이름은 무엇인가요?");
            assertFalse(replay.created());assertEquals(turn,replay.turnId());
            assertTrue(store.page("other-owner","live",null,10).turns().isEmpty());
        }
    }
}
