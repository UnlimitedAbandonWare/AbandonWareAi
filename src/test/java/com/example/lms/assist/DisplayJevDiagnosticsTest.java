package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DEMO1-DEVIN-META-DISPLAY-JEV-PORT-20260930 S2/S4: 개발자 진단은 cue의 Jev 판단을
 *  shadow 관측용으로 노출하지만, 공개·렌즈 projection은 어떤 Jev 문자열도 싣지 않는다. */
class DisplayJevDiagnosticsTest {
    ConversateSessionService sessions;ClientOwnerKeyResolver owners;DisplayConversateController controller;
    MockHttpServletRequest http;final ObjectMapper json=new ObjectMapper();
    final String client="12345678123442348234123456789abc";
    @BeforeEach void setup(){
        sessions=spy(new ConversateSessionService());owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-owner");
        controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
        http=new MockHttpServletRequest();http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");
    }
    @AfterEach void close(){sessions.close();}
    @SuppressWarnings("unchecked")
    static Map<String,Object> pipeline(Map<String,Object> cue){
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(DisplayConversateController.class,"pipelineDiagnostics",cue,true);
    }
    @Test void cueJevFieldsAppearWhenModeIsShadow(){
        var cue=new LinkedHashMap<String,Object>();
        cue.put("jevMode","shadow");cue.put("jevDecision","WEB");cue.put("jevReasonCode","ok");cue.put("jevApplied",false);
        var result=pipeline(cue);
        assertEquals("shadow",result.get("jevMode"));assertEquals("WEB",result.get("jevDecision"));
        assertEquals("ok",result.get("jevReasonCode"));assertEquals(false,result.get("jevApplied"));
    }
    @Test void cueJevFieldsOmittedWhenOff(){
        var off=new LinkedHashMap<String,Object>();
        off.put("jevMode","off");off.put("jevDecision","off");off.put("jevReasonCode","disabled");off.put("jevApplied",false);
        var absent=new LinkedHashMap<String,Object>();
        absent.put("cueDecision","CUE");absent.put("status","GENERATED");
        for(var cue:List.of(off,absent))
            for(String key:pipeline(cue).keySet())assertFalse(key.startsWith("jev"),key);
    }
    @Test void latencyOnlyAcceptsNonnegativeIntegerOrLong(){
        for(Object latency:List.of(0,37L,Long.MAX_VALUE))
            assertEquals(latency,pipeline(Map.of("jevMode","on","jevLatencyMs",latency)).get("jevLatencyMs"));
        for(Object latency:List.of(-1,-1L,"37",37.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertFalse(pipeline(Map.of("jevMode","shadow","jevLatencyMs",latency)).containsKey("jevLatencyMs"));
    }
    @Test void defaultAndPublicPhoneTestExcludeInjectedJevDiagnostics(){
        var cue=Map.<String,Object>of("jevMode","on","jevDecision","WEB","jevReasonCode","ok","jevApplied",true,"jevLatencyMs",37L);
        Map<?,?> defaults=org.springframework.test.util.ReflectionTestUtils.invokeMethod(DisplayConversateController.class,"pipelineDiagnostics",cue);
        assertFalse(defaults.keySet().stream().anyMatch(key->key.toString().startsWith("jev")));
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http);
        Map<?,?> bindings=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(controller,"bindings");
        var snapshot=mock(ConversateSessionService.Snapshot.class,RETURNS_DEEP_STUBS);
        when(snapshot.metrics().stages().cue()).thenReturn(cue);
        Map<?,?> status=org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller,"testStatus",bindings.values().iterator().next(),snapshot,client);
        Map<?,?> publicPipeline=(Map<?,?>)status.get("pipeline");
        assertFalse(publicPipeline.keySet().stream().anyMatch(key->key.toString().startsWith("jev")));
    }
    @Test void unsafeJevValuesDropped(){
        var cue=new LinkedHashMap<String,Object>();
        cue.put("jevMode","shadow");cue.put("jevDecision","defer");cue.put("jevApplied",false);
        cue.put("jevReasonCode","<b>x</b>");
        var result=pipeline(cue);
        assertEquals("shadow",result.get("jevMode"));assertFalse(result.containsKey("jevReasonCode"));
        cue.put("jevReasonCode","r".repeat(101));
        assertFalse(pipeline(cue).containsKey("jevReasonCode"));
    }
    @Test void actualDeveloperEndpointIncludesLatencyOnlyAfterAuthorization(){
        var view=controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();
        var snapshot=mock(ConversateSessionService.Snapshot.class,RETURNS_DEEP_STUBS);
        when(snapshot.reason()).thenReturn("synthetic");
        when(snapshot.metrics().stages().cue()).thenReturn(Map.of("jevMode","on","jevLatencyMs",37L));
        doReturn(snapshot).when(sessions).status(anyString(),anyString());
        var request=new DisplayConversateController.DiagnosticRequest(view.assistId(),true);
        var denied=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.diagnostics(request,http,null));
        assertEquals(403,denied.getStatusCode().value());
        var admin=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("synthetic-admin","unused",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var response=controller.diagnostics(request,http,admin);
        assertEquals(200,response.getStatusCode().value());
        assertEquals(37L,((Map<?,?>)((Map<?,?>)response.getBody()).get("pipeline")).get("jevLatencyMs"));
    }
    @Test void publicAndLensProjectionsNeverCarryJev()throws Exception{
        http.setParameter("debug","true");
        var view=controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();
        assertFalse(json.valueToTree(view).toString().contains("jev"),"public view must not carry jev");
        var lens=controller.lens(new DisplayConversateController.Connection(null,0,client),http).getBody();
        assertFalse(json.valueToTree(lens).toString().contains("jev"),"lens view must not carry jev");
        var text=new DisplayConversateController.LensText("conversation","hint","req-1",1,2,null,null);
        var lensTree=json.valueToTree(text);
        var fields=new HashSet<String>();lensTree.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("conversation","hint","hintId","hintExpiresAt","conversationExpiresAt","display","focus"),fields);
        assertFalse(lensTree.toString().toLowerCase(Locale.ROOT).contains("jev"));
    }
}
