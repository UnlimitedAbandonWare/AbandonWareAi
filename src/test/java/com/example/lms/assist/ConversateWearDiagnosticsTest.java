package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversateWearDiagnosticsTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void renderedPhaseIsIndependentIdempotentAndOwnerEpochBounded() throws Exception {
        try(var service=new ConversateSessionService()) {
            var s=service.start("owner");var output=service.output("owner",s.assistId(),s.epoch()).subscribe();
            try {
                assertTrue(service.publish("owner",s.assistId(),s.epoch(),new ConversateSessionService.Card("SHOW","FACT","Synthetic card",List.of(),System.currentTimeMillis()+60000)));
                s=service.status("owner",s.assistId());final String id=s.assistId();final long epoch=s.epoch(),version=s.version();
                var rendered=service.acknowledge("owner",id,epoch,version,"rendered");
                assertEquals(0,rendered.metrics().outputAcks());assertEquals(1,rendered.diagnostics().renderedCount());assertEquals(version,rendered.version());
                assertEquals(1,service.acknowledge("owner",id,epoch,version,"rendered").diagnostics().renderedCount());
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.acknowledge("other",id,epoch,version,"rendered"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.acknowledge("owner",id,epoch,version+99,"rendered"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.acknowledge("owner",id,epoch,version,"physical_read"));
                service.control("owner",id,epoch,"pause");
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.acknowledge("owner",id,epoch,version,"rendered"));
            } finally {output.dispose();}
        }
    }
    @Test void publishedCardKeepsItsRequestWhenLaterContextArrives() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pipeline=new ConversateAnswerPipeline(){@Override public Outcome answerWithContext(String q,List<String> ctx,List<PreparedMaterialReader.Material> docs,long now){
            entered.countDown();try{release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return new Outcome("MATCH",new ConversateSessionService.Card("SHOW","FACT","Synthetic answer",List.of(),now+20000));
        }};
        try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),pipeline)) {
            String owner="a".repeat(64);var s=service.start(owner);
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u1","q1",1,true,"어떤 차이가 있나요?"),"direct","request-original");
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u2","q2",1,true,"아, 네"),"direct","request-context");release.countDown();
            for(int i=0;i<100&&service.status(owner,s.assistId()).card()==null;i++)Thread.sleep(10);
            var card=service.status(owner,s.assistId()).card();assertNotNull(card);assertEquals("request-original",card.requestId());
        } finally {release.countDown();}
    }
    @Test void diagnosticsCorrelateFinalWithoutRetainingItsTextAndReceiptNeverMeansRendering() {
        try(var service=new ConversateSessionService()) {
            var s=service.start("synthetic-owner");
            service.submit("synthetic-owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("safe-u1","safe-q1",1,true,"아, 네"));
            var state=json.valueToTree(service.status("synthetic-owner",s.assistId()));
            var d=state.path("diagnostics");
            assertEquals("BACKCHANNEL",d.path("inputDecision").asText());
            assertTrue(d.path("lastFinalAt").asLong()>0);
            assertFalse(d.toString().contains("아, 네"));
            var output=service.output("synthetic-owner",s.assistId(),s.epoch()).subscribe();
            try {
                s=service.status("synthetic-owner",s.assistId());
                var ack=json.valueToTree(service.acknowledge("synthetic-owner",s.assistId(),s.epoch(),s.version()));
                assertEquals(1,ack.path("metrics").path("outputAcks").asInt());
                assertEquals(0,ack.path("diagnostics").path("renderedCount").asInt(-1));
                assertEquals("not_observed",ack.path("diagnostics").path("lensVerification").asText());
            } finally {output.dispose();}
        }
    }
    @Test void imperativeWithoutQuestionMarkIsSelectedButQuotedInstructionIsContext() {
        var policy=new ConversateQuestionPolicy();
        assertEquals("QUESTION",policy.accept(new ConversateQuestionPolicy.Utterance("u1","q1",1,true,"검색 증강 생성을 설명해 주세요.")).kind());
        assertEquals("NEW_INFORMATION",policy.accept(new ConversateQuestionPolicy.Utterance("u2","q2",1,true,"그는 설명해 주세요라는 표현을 썼다.")).kind());
    }
    @Test void bootstrapExposesNonSensitiveArtifactFingerprint() {
        try(var service=new ConversateSessionService()) {
            var controller=new ConversateController(service);
            var auth=new org.springframework.security.authentication.TestingAuthenticationToken("fixture","unused","ROLE_USER");
            var body=json.valueToTree(controller.bootstrap(auth,new org.springframework.mock.web.MockHttpServletRequest()).getBody());
            assertTrue(body.path("buildId").asText().matches("[a-f0-9]{16}"));
        }
    }
}
