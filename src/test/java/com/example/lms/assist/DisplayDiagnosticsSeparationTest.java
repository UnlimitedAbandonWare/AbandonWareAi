package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.lms.assist.ConversateSessionService.*;

class DisplayDiagnosticsSeparationTest {
    ConversateSessionService sessions;ClientOwnerKeyResolver owners;DisplayConversateController controller;
    MockHttpServletRequest http;final ObjectMapper json=new ObjectMapper();
    final String client="12345678123442348234123456789abc";
    final UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("developer",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    @BeforeEach void setup(){
        sessions=new ConversateSessionService();owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-owner");
        controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
        http=new MockHttpServletRequest();http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");
    }
    @AfterEach void close(){sessions.close();}
    @Test void pipelineProjectionKeepsAttemptCostsAndReasonsWithoutProviderBodies()throws Exception{
        var attempt=new LinkedHashMap<String,Object>();
        attempt.put("model","gpt-5.6-luna");attempt.put("status","GENERATION_INVALID_OUTPUT");
        attempt.put("fallbackReason","none");attempt.put("outputValidation","evidence_missing");
        attempt.put("reasoningTokens",81);attempt.put("usageEstimatedCostUsd",.0002);
        attempt.put("outputHash","a".repeat(64));attempt.put("outputTextChars",63);attempt.put("outputEvidenceCount",0);
        attempt.put("actualTokens",Map.of("input",400,"output",120,"raw","private-token-usage"));
        attempt.put("rawResponse","private-response-marker");attempt.put("prompt","private-prompt-marker");
        var cue=new LinkedHashMap<>(Map.<String,Object>of("hintAttempts",Collections.nCopies(5,attempt),"observedReasoningTokens",81,
                "billedCostUsd","not_observed","stageCalls",Map.of("finalGeneration",1,"raw","private-stage"),
                "searchNeeded",true,"search.NAVER",Map.of("requestCount",1,"resultCount",3,"raw","private-search",
                        "clientAttemptCount",1,"httpResponseCount",1,"clientAttemptCoverage","observed"),
                "retrieval.dropReasonCounts",Map.of("host_duplicate",2,"raw","private-candidate")));
        cue.put("contextChars",99);cue.put("evidenceChars",770);cue.put("hintHash","b".repeat(64));
        cue.put("hintPath","GENERAL_HINT");cue.put("evidenceStatus","FRAGMENTED");cue.put("evidenceInsufficient",true);
        cue.put("usableEvidenceCount",0);cue.put("fallbackReason","EVIDENCE_INSUFFICIENT");
        cue.put("supplementStatus","RETAINED_GENERAL_HINT");cue.put("supplementFailure","GENERATION_TIMEOUT");
        cue.put("transcriptHash","c".repeat(64));
        cue.put("evidenceDigests",List.of(Map.of("id","e0","sha256","d".repeat(64),"text","private-evidence"),
                Map.of("id","e1","sha256","private-not-a-hash"),Map.of("id","private-id","sha256","e".repeat(64))));
        cue.put("citedEvidenceIds",List.of("e0","e0","e1","private-id"));
        Object result=org.springframework.test.util.ReflectionTestUtils.invokeMethod(DisplayConversateController.class,"pipelineDiagnostics",cue);
        var tree=json.valueToTree(result);
        assertEquals(3,tree.path("hintAttempts").size());
        var first=tree.path("hintAttempts").get(0);
        assertEquals("evidence_missing",first.path("outputValidation").asText());
        assertEquals(81,first.path("reasoningTokens").asInt());
        assertEquals("a".repeat(64),first.path("outputHash").asText());
        assertEquals(63,first.path("outputTextChars").asInt());
        assertEquals(99,tree.path("contextChars").asInt());
        assertEquals(770,tree.path("evidenceChars").asInt());
        assertEquals("GENERAL_HINT",tree.path("hintPath").asText());assertEquals("FRAGMENTED",tree.path("evidenceStatus").asText());
        assertTrue(tree.path("evidenceInsufficient").asBoolean());assertEquals(0,tree.path("usableEvidenceCount").asInt());
        assertEquals("EVIDENCE_INSUFFICIENT",tree.path("fallbackReason").asText());
        assertEquals("RETAINED_GENERAL_HINT",tree.path("supplementStatus").asText());assertEquals("GENERATION_TIMEOUT",tree.path("supplementFailure").asText());
        assertEquals("b".repeat(64),tree.path("hintHash").asText());
        assertEquals("c".repeat(64),tree.path("transcriptHash").asText());
        assertEquals(1,tree.path("evidenceDigests").size());
        assertEquals("d".repeat(64),tree.path("evidenceDigests").get(0).path("sha256").asText());
        assertEquals(json.readTree("[\"e0\"]"),tree.path("citedEvidenceIds"));
        assertEquals(400,first.path("actualTokens").path("input").asInt());
        assertEquals(1,tree.path("stageCalls").path("finalGeneration").asInt());
        assertEquals(3,tree.path("search.NAVER").path("resultCount").asInt());
        assertEquals(1,tree.path("search.NAVER").path("clientAttemptCount").asInt());
        assertEquals(1,tree.path("search.NAVER").path("httpResponseCount").asInt());
        assertEquals("observed",tree.path("search.NAVER").path("clientAttemptCoverage").asText());
        assertEquals(2,tree.path("retrieval.dropReasonCounts").path("host_duplicate").asInt());
        assertFalse(tree.toString().contains("private-"));
    }
    @Test void configuredCloudModelIsBoundedAndSeparateFromProviderObservedModel()throws Exception{
        var cloud=mock(ConversateCloudStt.class);
        when(cloud.budgetView()).thenThrow(new java.io.IOException("synthetic accounting unavailable"));
        var bridge=new ConversateAsrBridge(sessions,json,(ConversateAsrBridge.Factory)null,cloud);
        try{
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"asr",bridge);
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
            for(String configured:List.of("stt-rt-v5","stt-rt-v4","nova-3","whisper-large-v3-turbo","gemini-3.5-flash-lite","private-model-marker")){
                when(cloud.diagnostics()).thenReturn(Map.of("provider","soniox","model",configured,"routing","fixed","state","ready"));
                String expected=configured.equals("private-model-marker")?"not_observed":configured;
                var view=controller.phoneTest(new DisplayConversateController.Connection(null,0,client),http).getBody();
                var phone=json.valueToTree(view).path("testStatus").path("asr");
                assertEquals(expected,phone.path("configuredCloudModel").asText());
                assertEquals("not_observed",phone.path("model").asText());
                var developer=json.valueToTree(controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),true),http,admin).getBody()).path("audio");
                assertEquals(expected,developer.path("configuredCloudModel").asText());
                assertEquals("not_observed",developer.path("model").asText());
                assertFalse(phone.toString().contains("private-model-marker"));assertFalse(developer.toString().contains("private-model-marker"));
            }
        }finally{bridge.close();}
    }
    DisplayConversateController.View start(){return controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();}
    @Test void publicProjectionExcludesDiagnosticsEvenWithDebugOptions()throws Exception{
        http.setParameter("debug","true");http.addHeader("X-Debug","true");var view=start();var tree=json.valueToTree(view);
        for(String key:List.of("audioRuntime","metrics","diagnostics","processingMs","duplicates","audioChunks","audioPartials","audioFinals","inputDecision","captionRendered"))assertFalse(tree.has(key),key);
        assertTrue(tree.has("assistId"));assertTrue(tree.has("epoch"));assertTrue(tree.has("version"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),true),http,null)).getStatusCode().value());
        var user=new UsernamePasswordAuthenticationToken("user",null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),true),http,user)).getStatusCode().value());
    }
    @Test void developerMustExplicitlyEnableAndOwnTheSessionWithoutChangingItsLifecycle()throws Exception{
        var view=start();String owner=org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-owner");
        Snapshot before=sessions.status(owner,view.assistId());
        assertEquals(Map.of("enabled",false),controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),false),http,admin).getBody());
        for(int i=0;i<4;i++){
            var tree=json.valueToTree(controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),true),http,admin).getBody());
            assertTrue(tree.path("enabled").asBoolean());assertTrue(tree.has("audioState"));assertTrue(tree.has("lastAudioReceivedAt"));assertTrue(tree.has("pipeline"));
            assertFalse(tree.has("caption"));assertFalse(tree.has("card"));
        }
        Snapshot after=sessions.status(owner,view.assistId());
        assertEquals(before.epoch(),after.epoch());assertEquals(before.version(),after.version());assertEquals(before.outputConnections(),after.outputConnections());assertEquals(before.audio(),after.audio());assertEquals(before.metrics().generationAttempts(),after.metrics().generationAttempts());
        when(owners.ownerKey()).thenReturn("foreign-owner");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.diagnostics(new DisplayConversateController.DiagnosticRequest(view.assistId(),true),http,admin)).getStatusCode().value());
    }
    @Test void contentProjectionDropsStatusFailuresAndWordTelemetry()throws Exception{
        long now=System.currentTimeMillis();
        for(String kind:List.of("STATUS","FALLBACK","CLARIFICATION","TEST"))assertNull(DisplayContentView.card(new Card("SHOW",kind,"provider error",List.of(),now+15000),now));
        assertNull(DisplayContentView.card(new Card("HOLD","FACT","stale",List.of(),now+15000),now));
        assertNull(DisplayContentView.card(new Card("SHOW","FACT","expired",List.of(),now-1),now));
        var card=DisplayContentView.card(new Card("SHOW","RAG","short hint",List.of("internal-source"),now+15000,"request-1",List.of("short source")),now);
        var tree=json.valueToTree(card);assertFalse(tree.has("sourceIds"));assertFalse(tree.has("decision"));assertEquals("short hint",tree.path("text").asText());
        var caption=DisplayContentView.caption(new Caption("utterance",1,true,"transcript",.99,List.of(),now,now+15000),now);
        var c=json.valueToTree(caption);assertFalse(c.has("confidence"));assertFalse(c.has("words"));assertFalse(c.has("receivedAt"));assertEquals("transcript",c.path("text").asText());
    }
    @Test void lensWireShapeContainsOnlyContentAndRequiredControl()throws Exception{
        http.setParameter("debug","true");
        var view=controller.lens(new DisplayConversateController.Connection(null,0,client),http).getBody();
        var fields=new HashSet<String>();json.valueToTree(view).fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("assistId","epoch","version","ready","card","caption","captionTtlMs","cardTtlMs"),fields);
    }
    @Test void successfulCuePollKeepsContentAndGenericSuccessWhileFailureStaysFailure(){
        sessions=spy(sessions);controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
        var started=start();String owner=org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-owner");
        var s=sessions.status(owner,started.assistId());
        for(String kind:List.of("CUE","RAG_CUE")){
            var card=new Card("SHOW",kind,"short successful hint",List.of(),System.currentTimeMillis()+15000,"cue-request",List.of("short source"));
            doReturn(new Snapshot(s.assistId(),s.epoch(),s.state(),card,s.version(),s.outputConnections(),"API_CUE",s.metrics(),s.audio(),s.diagnostics(),s.caption()))
                    .when(sessions).pollOutput(owner,s.assistId(),s.epoch(),client);
            var view=controller.poll(new DisplayConversateController.Connection(s.assistId(),s.epoch(),client),http).getBody();
            assertEquals("CONTENT_READY",view.reason());assertEquals(kind,view.card().kind());assertEquals("short successful hint",view.card().text());
        }
        doReturn(new Snapshot(s.assistId(),s.epoch(),s.state(),null,s.version(),s.outputConnections(),"GENERATION_TIMEOUT",s.metrics(),s.audio(),s.diagnostics(),s.caption()))
                .when(sessions).pollOutput(owner,s.assistId(),s.epoch(),client);
        var failed=controller.poll(new DisplayConversateController.Connection(s.assistId(),s.epoch(),client),http).getBody();
        assertEquals("PROCESSING_FAILED",failed.reason());assertNull(failed.card());
    }
}

@org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest(controllers=DisplayConversateController.class,useDefaultFilters=false)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters=false)
@org.springframework.context.annotation.Import({DisplayConversateController.class,ConversateSessionService.class,InterviewDemoPublicAddress.class,
        com.example.lms.config.AppSecurityConfig.class,com.example.lms.security.ChatOpenSecurityConfig.class,com.example.lms.security.AdminTokenGuardInterceptor.class,com.example.lms.api.PublicChatAdmissionGuard.class})
@org.springframework.test.context.TestPropertySource(properties={"conversate.enabled=true","conversate.display.enabled=true","demo.interview.enabled=true","security.force-https=false","domain.allowlist.admin-token.required=false","security.bootstrap-admin.password="})
class DisplayDiagnosticsSecurityIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.web.context.WebApplicationContext context;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.security.web.FilterChainProxy security;
    @org.springframework.beans.factory.annotation.Autowired com.example.lms.security.ChatOpenSecurityConfig open;
    @org.springframework.beans.factory.annotation.Autowired InterviewDemoPublicAddress address;
    @org.springframework.boot.test.mock.mockito.MockBean com.example.lms.repository.AdministratorRepository administrators;
    @org.springframework.boot.test.mock.mockito.MockBean com.example.lms.service.AdminDetailsServiceImpl details;
    @org.springframework.boot.test.mock.mockito.MockBean com.example.lms.service.AdminService adminService;
    @org.springframework.boot.test.mock.mockito.MockBean ClientOwnerKeyResolver owners;
    org.springframework.test.web.servlet.MockMvc mvc;
    @BeforeEach void setup(){when(owners.ownerKey()).thenReturn("security-fixture-owner");mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).addFilters(open.interviewDemoFilter(address).getFilter(),security).build();}
    org.springframework.mock.web.MockHttpSession session(String role){
        var session=new org.springframework.mock.web.MockHttpSession();var auth=new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority(role)));
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,new org.springframework.security.core.context.SecurityContextImpl(auth));return session;
    }
    @Test void realDemoFilterAndSecurityChainEnforceRoleOwnershipAndOptIn()throws Exception{
        String request="{\"assistId\":null,\"epoch\":0,\"clientId\":\"12345678123442348234123456789abc\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/assist/display/lens").header("Origin","http://localhost").header("X-Display-Client","1").contentType("application/json").content(request)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/diagnostics/display").param("enabled","true").param("debug","true").header("X-Display-Client","1").header("Sec-Fetch-Site","same-origin");
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Location"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/diagnostics/display").session(session("ROLE_USER")).param("enabled","true").header("X-Display-Client","1").header("Sec-Fetch-Site","same-origin")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var admin=session("ROLE_ADMIN");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/diagnostics/display").session(admin).header("X-Display-Client","1").header("Sec-Fetch-Site","same-origin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.enabled").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/diagnostics/display").session(admin).param("enabled","true").header("X-Display-Client","1").header("Sec-Fetch-Site","same-origin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.audioChunks").value(0)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.caption").doesNotExist());
        when(owners.ownerKey()).thenReturn("foreign-cookie-owner");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/diagnostics/display").session(admin).param("enabled","true").header("X-Display-Client","1").header("Sec-Fetch-Site","same-origin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
}
