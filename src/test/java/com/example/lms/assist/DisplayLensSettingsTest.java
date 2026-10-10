package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayLensSettingsTest {
    @Test void legacyTranscriptBandsRoundTripAsThreeAndInvalidBoundsRemainRejected() throws Exception {
        for(int lines:new int[]{1,2}){
            var upgraded=LensDisplayPrefs.defaults(540).patch(mapper.readValue("{\"transcriptMaxLines\":"+lines+"}",LensDisplayPrefs.Patch.class));
            assertEquals(3,upgraded.transcriptMaxLines());
            assertEquals(3,LensDisplayPrefs.defaults(540).patch(mapper.readValue(mapper.writeValueAsString(upgraded),LensDisplayPrefs.Patch.class)).transcriptMaxLines());
        }
        for(int lines:new int[]{0,9})assertThrows(Exception.class,()->LensDisplayPrefs.defaults(540).patch(mapper.readValue("{\"transcriptMaxLines\":"+lines+"}",LensDisplayPrefs.Patch.class)));
    }
    final ObjectMapper mapper=new ObjectMapper();
    @Test void autoVoiceSettingsAreIndependentStrictAndRoundTrip() throws Exception {
        var defaults=LensDisplayPrefs.defaults(540);
        var patch=mapper.readValue("""
            {"autoVoiceTrigger":{"modeEnabled":true,"hintsEnabled":false,"language":"ko",
              "hintLines":3,"hintChars":200,"phrases":[{"id":"one","language":"ko","text":"  안녕  "},
              {"id":"two","language":"ko","text":"안녕"}]}}
            """,LensDisplayPrefs.Patch.class);
        var applied=defaults.patch(patch);
        var json=mapper.valueToTree(applied).path("autoVoiceTrigger");
        assertTrue(json.path("modeEnabled").asBoolean());assertFalse(json.path("hintsEnabled").asBoolean());
        assertEquals(1,json.path("phrases").size());assertEquals("안녕",json.path("phrases").get(0).path("text").asText());
        assertEquals(defaults.hintTargetChars(),applied.hintTargetChars());
        assertEquals(defaults,defaults.patch(mapper.readValue("{}",LensDisplayPrefs.Patch.class)));
        assertEquals(applied,defaults.patch(mapper.readValue(mapper.writeValueAsString(applied),LensDisplayPrefs.Patch.class)));
        assertFalse(mapper.writeValueAsString(applied.describe()).contains("안녕"));
        var off=applied.patch(mapper.readValue("{\"autoVoiceTrigger\":{\"modeEnabled\":false}}",LensDisplayPrefs.Patch.class));
        assertFalse(mapper.valueToTree(off).path("autoVoiceTrigger").path("modeEnabled").asBoolean());
    }
    @Test void autoVoiceInvalidValuesAreRejectedWithoutClamp() {
        for(String json:new String[]{"{\"hintLines\":2}","{\"hintLines\":10}","{\"hintChars\":199}",
                "{\"hintChars\":501}","{\"hintLines\":3.5}","{\"hintChars\":\"200\"}",
                "{\"language\":\"xx\"}","{\"modeEnabled\":true}","{\"phrases\":[{\"id\":\"x\",\"language\":\"ko\",\"text\":\" \"}]}",
                "{\"phrases\":[{\"id\":\"x\",\"language\":\"ko\",\"text\":\"a\\u0000b\"}]}",
                "{\"phrases\":[{\"id\":\"x\",\"language\":\"ko\",\"text\":\"\\ud800\"}]}"})
            assertThrows(Exception.class,()->LensDisplayPrefs.defaults(540).patch(mapper.readValue("{\"autoVoiceTrigger\":"+json+"}",LensDisplayPrefs.Patch.class)),json);
    }
    @Test void fontJsonRejectsFractionAndBlankInsteadOfCoercing() throws Exception {
        for(String raw:new String[]{"20.5","\"\"","\"26\"","2147483648"})
            assertThrows(Exception.class,()->mapper.readValue("{\"hintFontPx\":"+raw+"}",LensDisplayPrefs.Patch.class),raw);
        for(int font:new int[]{20,26,36}){
            var patch=mapper.readValue("{\"hintFontPx\":"+font+"}",LensDisplayPrefs.Patch.class);
            var value=LensDisplayPrefs.defaults(540).patch(patch);assertEquals(font,value.hintFontPx());
            assertEquals(LensDisplayPrefs.defaults(540).hintTargetChars(),value.hintTargetChars());
        }
        for(int invalid:new int[]{19,37})assertThrows(ResponseStatusException.class,()->LensDisplayPrefs.defaults(540).patch(mapper.readValue("{\"hintFontPx\":"+invalid+"}",LensDisplayPrefs.Patch.class)));
    }
    @Test void revisionConflictAndOwnerScopeAreEchoedWithoutGenerating() throws Exception {
        var sessions=new ConversateSessionService();var owners=mock(ClientOwnerKeyResolver.class);
        when(owners.ownerKey()).thenReturn("synthetic-a");var controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
        ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        var http=new MockHttpServletRequest();http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");
        String client="12345678123442348234123456789abc";
        try{
            var connection=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            var json=mapper.valueToTree(Map.of("assistId",connection.assistId(),"epoch",connection.epoch(),"clientId",client,"display",Map.of("hintFontPx",36),"expectedSettingsVersion",0));
            var request=assertDoesNotThrow(()->mapper.treeToValue(json,DisplayConversateController.LensSettings.class));
            var applied=controller.lensSettings(request,http).getBody();
            assertEquals(1L,((Number)applied.testStatus().get("lensSettingsVersion")).longValue());
            assertTrue(applied.testStatus().get("lensSettingsScope") instanceof String);
            assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.lensSettings(request,http)).getStatusCode().value());
            when(owners.ownerKey()).thenReturn("synthetic-b");
            var other=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            assertNotEquals(applied.testStatus().get("lensSettingsScope"),other.testStatus().get("lensSettingsScope"));
            assertEquals(26,((Map<?,?>)other.testStatus().get("lensDisplay")).get("hintFontPx"));
        }finally{sessions.close();}
    }
    @Test void durableCasAllowsOneWriterAndRefreshesTheConflictingControllersCache() throws Exception {
        var fixture=new NovaFocusRestartPersistenceTest();
        try(var database=fixture.open("jdbc:h2:mem:lens-cas-"+java.util.UUID.randomUUID()+";MODE=MariaDB;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
            var firstSessions=new ConversateSessionService();var secondSessions=new ConversateSessionService()){
            var owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-concurrent-owner");
            var first=fixture.display(database.history(),firstSessions,owners);var second=fixture.display(database.history(),secondSessions,owners);
            var http=fixture.displayRequest("test-1234567812345678");String client="12345678123442348234123456789abc";
            var a=first.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            var b=second.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
            var start=new java.util.concurrent.CountDownLatch(1);var writers=java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Callable<Integer> writeA=()->{start.await();try{first.lensSettings(request(a,client,36,0),http);return 200;}catch(ResponseStatusException conflict){return conflict.getStatusCode().value();}};
            java.util.concurrent.Callable<Integer> writeB=()->{start.await();try{second.lensSettings(request(b,client,20,0),http);return 200;}catch(ResponseStatusException conflict){return conflict.getStatusCode().value();}};
            try{
                var one=writers.submit(writeA);var two=writers.submit(writeB);start.countDown();
                assertEquals(java.util.List.of(200,409),java.util.stream.Stream.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS)).sorted().toList());
            }finally{writers.shutdownNow();}
            var freshFirst=first.phoneTest(new DisplayConversateController.Connection(a.assistId(),a.epoch(),client),http).getBody();
            var freshSecond=second.phoneTest(new DisplayConversateController.Connection(b.assistId(),b.epoch(),client),http).getBody();
            assertEquals(1L,((Number)freshFirst.testStatus().get("lensSettingsVersion")).longValue());
            assertEquals(freshFirst.testStatus().get("lensDisplay"),freshSecond.testStatus().get("lensDisplay"));
            assertEquals("profile_db",freshFirst.testStatus().get("lensSettingsPersistence"));
            var em=database.factory().getObject().createEntityManager();
            try{assertEquals(0L,em.createQuery("select count(p) from NovaFocusProfile p where p.chatSession is not null",Long.class).getSingleResult());}
            finally{em.close();}
        }
    }
    private DisplayConversateController.LensSettings request(DisplayConversateController.View view,String client,int font,long version) throws Exception {
        return mapper.treeToValue(mapper.valueToTree(Map.of("assistId",view.assistId(),"epoch",view.epoch(),"clientId",client,"display",Map.of("hintFontPx",font),"expectedSettingsVersion",version)),DisplayConversateController.LensSettings.class);
    }
}
