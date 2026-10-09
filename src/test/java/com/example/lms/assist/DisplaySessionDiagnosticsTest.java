package com.example.lms.assist;

import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplaySessionDiagnosticsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String client="12345678123442348234123456789abc";
    private final String owner=org.apache.commons.codec.digest.DigestUtils.sha256Hex("public-display:synthetic-owner");
    private final UsernamePasswordAuthenticationToken admin=new UsernamePasswordAuthenticationToken("developer",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private MockHttpServletRequest request(){
        var http=new MockHttpServletRequest();http.setScheme("https");http.setServerName("example.test");http.setServerPort(443);
        http.addHeader("Origin","https://example.test");http.addHeader("X-Display-Client","1");return http;
    }

    @Test void stopPreservesSameCaptureWithoutAnIdAndKeepsContentLifecycle() throws Exception {
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T00:00:00Z"),java.time.ZoneOffset.UTC);
        try(var sessions=new ConversateSessionService(clock)){
            var owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-owner");
            var controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
            ReflectionTestUtils.setField(controller,"audioEnabled",true);
            ReflectionTestUtils.setField(controller,"asr",mock(ConversateAsrBridge.class));
            var http=request();var view=controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();
            String id=view.assistId();long epoch=view.epoch();var closed=new AtomicInteger();
            sessions.pollOutput(owner,id,epoch,client);
            sessions.registerCapture(owner,id,epoch,()->{closed.incrementAndGet();throw new IllegalStateException("synthetic-close-failure");});
            sessions.audioMetrics(owner,id,epoch,new ConversateSessionService.AudioMetrics(2,1,1,0,4,"LISTENING"));
            sessions.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u1","u1",1,true,"private-transcript-marker"),"phone_voice",null);
            var before=sessions.status(owner,id);assertNotNull(before.diagnostics().finalTranscriptAt());
            sessions.acknowledge(owner,id,epoch,before.version(),"caption_rendered");
            assertTrue(sessions.publish(owner,id,epoch,new ConversateSessionService.Card("SHOW","FACT","private-hint-marker",List.of(),System.currentTimeMillis()+60000)));
            before=sessions.status(owner,id);sessions.acknowledge(owner,id,epoch,before.version(),"rendered");
            before=sessions.status(owner,id);
            controller.audioStop(new DisplayConversateController.AudioStop(id,epoch,client,false),http);
            var result=json.valueToTree(controller.diagnosticsAccess(null,true,http,admin).getBody());
            var capture=result.path("capture");
            assertEquals(id,result.path("assistId").asText());assertEquals("closed",capture.path("state").asText());
            assertEquals(epoch,capture.path("epoch").asLong());
            assertEquals(before.diagnostics().firstTranscriptAt().longValue(),capture.path("firstTranscriptAt").asLong());
            assertEquals(before.diagnostics().finalTranscriptAt().longValue(),capture.path("finalTranscriptAt").asLong());
            assertEquals(before.diagnostics().captionRenderedAt().longValue(),capture.path("captionRenderedAt").asLong());
            assertEquals(before.diagnostics().hintRenderedAt().longValue(),capture.path("hintRenderedAt").asLong());
            assertEquals("not_observed",capture.path("hardwareRendered").asText());
            assertEquals(1,closed.get());assertNull(sessions.status(owner,id).caption());
            // Existing text fallback keeps the already-published hint until its own TTL.
            assertEquals(before.card(),sessions.status(owner,id).card());
            assertNull(sessions.status(owner,id).diagnostics().firstTranscriptAt());
            assertFalse(result.toString().contains("private-transcript-marker"));assertFalse(result.toString().contains("private-hint-marker"));
            assertEquals(result.path("capture"),json.valueToTree(controller.diagnosticsAccess(null,true,http,admin).getBody()).path("capture"));
            when(owners.ownerKey()).thenReturn("foreign-owner");
            assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.diagnosticsAccess(null,true,http,admin)).getStatusCode().value());
        }
    }
    @Test void newCaptureDoesNotMergePriorRenderOrReusedProviderIdsAndReadsDoNotRenewIt(){
        try(var sessions=new ConversateSessionService()){
            var s=sessions.startPublicDisplay(owner);String id=s.assistId();long epoch=s.epoch();
            sessions.pollOutput(owner,id,epoch,client);sessions.registerCapture(owner,id,epoch,()->{});
            sessions.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("reused-u","q1",1,true,"private-first-marker"),"phone_voice",null);
            var version=sessions.status(owner,id).version();sessions.acknowledge(owner,id,epoch,version,"caption_rendered");
            int events=(int)sessions.captureDiagnostics(owner,id).get("eventsObserved");
            sessions.acknowledge(owner,id,epoch,version,"caption_rendered");
            assertEquals(events,sessions.captureDiagnostics(owner,id).get("eventsObserved"));
            var stopped=sessions.control(owner,id,epoch,"text_fallback");
            assertEquals("closed",sessions.captureDiagnostics(owner,id).get("state"));
            sessions.pollOutput(owner,id,stopped.epoch(),client);sessions.registerCapture(owner,id,stopped.epoch(),()->{});
            var active=sessions.captureDiagnostics(owner,id);assertEquals("active",active.get("state"));
            assertEquals(2L,active.get("captureRun"));assertEquals("not_observed",active.get("captionRenderedAt"));
            assertEquals("not_observed",active.get("finalTranscriptAt"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->sessions.acknowledge(owner,id,epoch,version,"caption_rendered"));
            sessions.submit(owner,id,stopped.epoch(),new ConversateQuestionPolicy.Utterance("reused-u","q1",1,true,"private-second-marker"),"phone_voice",null);
            assertNotEquals("not_observed",sessions.captureDiagnostics(owner,id).get("finalTranscriptAt"));
            assertEquals("not_observed",sessions.captureDiagnostics(owner,id).get("captionRenderedAt"));
            var before=sessions.status(owner,id);for(int i=0;i<5;i++)sessions.captureDiagnostics(owner,id);
            assertEquals(before.version(),sessions.status(owner,id).version());
            assertEquals(before.outputConnections(),sessions.status(owner,id).outputConnections());
            assertFalse(sessions.captureDiagnostics(owner,id).toString().contains("private-"));
        }
    }
    @Test void generatedHintCompletionSurvivesStopWithoutApiCueProvider() throws Exception {
        var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T00:00:00Z"),java.time.ZoneOffset.UTC);
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answerPublicDisplay(String question,List<String> context,long now,String requestId){
            return new Outcome("GENERATED",new ConversateSessionService.Card("SHOW","FACT","private-generated-marker",List.of(),now+60000));
        }};
        assertFalse(pipeline.usesApiCues());
        try(var sessions=new ConversateSessionService(clock,pipeline)){
            var owners=mock(ClientOwnerKeyResolver.class);when(owners.ownerKey()).thenReturn("synthetic-owner");
            var controller=new DisplayConversateController(sessions,owners,new InterviewDemoPublicAddress());
            ReflectionTestUtils.setField(controller,"audioEnabled",true);ReflectionTestUtils.setField(controller,"asr",mock(ConversateAsrBridge.class));
            var http=request();var view=controller.transcription(new DisplayConversateController.Connection(null,0,client),http).getBody();
            sessions.pollOutput(owner,view.assistId(),view.epoch(),client);sessions.registerCapture(owner,view.assistId(),view.epoch(),()->{});
            sessions.submit(owner,view.assistId(),view.epoch(),new ConversateQuestionPolicy.Utterance("u","q",1,true,"합성 질문인가요?"),"glasses_input",null);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            ConversateSessionService.Snapshot before;
            do {before=sessions.status(owner,view.assistId());if(before.diagnostics().hintCompletedAt()!=null)break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertNotNull(before.diagnostics().hintCompletedAt());assertNotNull(before.card());
            controller.audioStop(new DisplayConversateController.AudioStop(view.assistId(),view.epoch(),client,false),http);
            var result=json.valueToTree(controller.diagnosticsAccess(null,true,http,admin).getBody());
            assertEquals(before.diagnostics().hintCompletedAt().longValue(),result.path("capture").path("hintCompletedAt").asLong());
            assertFalse(result.toString().contains("private-generated-marker"));
        }
    }
    @Test void evictedStartIsNotReportedAsFirstTranscriptAndBufferRemainsBounded(){
        try(var sessions=new ConversateSessionService()){
            var s=sessions.startPublicDisplay(owner);sessions.registerCapture(owner,s.assistId(),s.epoch(),()->{});
            for(int i=1;i<=40;i++)sessions.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u","q",i,false,"private-partial-marker"),"phone_voice",null);
            var summary=sessions.captureDiagnostics(owner,s.assistId());
            assertEquals("bounded_window",summary.get("coverage"));assertEquals("not_observed",summary.get("startedAt"));
            assertEquals("not_observed",summary.get("firstTranscriptAt"));assertEquals("not_observed",summary.get("finalTranscriptAt"));
            assertTrue((int)summary.get("eventsObserved")<=24);
            assertEquals(24,((List<?>)sessions.transcriptDiagnostics(owner,s.assistId()).get("events")).size());
        }
    }
}
