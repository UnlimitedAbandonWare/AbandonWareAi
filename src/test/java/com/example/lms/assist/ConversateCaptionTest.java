package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversateCaptionTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void hintExceptionAndTimeoutPreserveFinalCaptionAndAllowFollowingSpeech() throws Exception {
        for(boolean timeout:new boolean[]{false,true}){
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var generator=new ConversateLocalCardGenerator((messages,schema)->{
                calls.incrementAndGet();
                if(timeout)throw new RuntimeException(new java.util.concurrent.TimeoutException());
                throw new IllegalStateException("synthetic generation failure");
            },100);
            try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),new ConversateAnswerPipeline(generator))){
                String owner="c".repeat(64);var initial=service.start(owner);String id=initial.assistId();long epoch=initial.epoch();
                var output=service.output(owner,id,epoch).subscribe();
                try{
                    service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u1","u1",1,false,"다음 행동을"),"phone_voice",null);
                    assertEquals(0,calls.get());
                    service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u1","u1",2,true,"다음 행동을 결정하기 어렵습니다."),"phone_voice",null);
                    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while(service.status(owner,id).card()==null&&System.nanoTime()<until)Thread.sleep(10);
                    var state=service.status(owner,id);assertEquals(1,calls.get());assertNotNull(state.card());
                    assertEquals(timeout?"GENERATION_TIMEOUT":"GENERATION_UNAVAILABLE",state.reason());
                    assertTrue(state.caption().isFinal());assertEquals("다음 행동을 결정하기 어렵습니다.",state.caption().text());
                    assertEquals("RUNNING",state.state());assertEquals(epoch,state.epoch());
                    var next=service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u2","u2",1,false,"다음 발화"),"phone_voice",null);
                    assertEquals("u2",next.caption().utteranceId());assertEquals(1,calls.get());
                }finally{output.dispose();}
            }
        }
    }
    @Test void newerNeutralSpeechInvalidatesSlowHintWithoutWaitingForGenerator() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var exited=new java.util.concurrent.CountDownLatch(1);
        var generator=new ConversateLocalCardGenerator((messages,schema)->{
            entered.countDown();
            while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            exited.countDown();return "{\"choice\":3}";
        },4000);
        try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),new ConversateAnswerPipeline(generator))){
            String owner="b".repeat(64);
            var initial=service.start(owner);String id=initial.assistId();long epoch=initial.epoch();
            var output=service.output(owner,id,epoch).subscribe();
            try{
                service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u1","u1",1,true,"다음 행동을 결정하기 어렵습니다."),"phone_voice",null);
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                var next=service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("u2","u2",1,false,"회의가 끝났습니다."),"phone_voice",null);
                assertEquals("u2",next.caption().utteranceId());assertNull(next.card());
                service.control(owner,id,epoch,"hints_off");
                release.countDown();assertTrue(exited.await(2,java.util.concurrent.TimeUnit.SECONDS));
                for(int i=0;i<100&&service.status(owner,id).metrics().inFlight()>0;i++)Thread.sleep(10);
                var finalState=service.status(owner,id);
                assertEquals(0,finalState.metrics().inFlight());assertNull(finalState.card());
                assertEquals("u2",finalState.caption().utteranceId());assertEquals(epoch,finalState.epoch());
            }finally{release.countDown();output.dispose();}
        }
    }
    @Test void bridgePreservesSpeechMetadataAndRenderAckIsVersionBoundedAndIdempotent() throws Exception {
        try(var service=new ConversateSessionService()) {
            var events=new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode>>();
            ConversateAsrBridge.Factory factory=(consumer,failure)->{events.set(consumer);consumer.accept(json.createObjectNode().put("type","ready"));return new ConversateAsrBridge.Transport(){
                public void send(String line){} public boolean alive(){return true;}public java.util.concurrent.CompletableFuture<Void> close(){return java.util.concurrent.CompletableFuture.completedFuture(null);}
            };};
            var bridge=new ConversateAsrBridge(service,json,factory);try{
                var s=service.start("owner");String id=s.assistId();long epoch=s.epoch();
                var output=service.output("owner",id,epoch).subscribe();
                try {
                    bridge.start("owner",id,epoch);
                    events.get().accept(json.readTree("{\"type\":\"transcript\",\"utteranceId\":\"dg-0\",\"revision\":1,\"final\":false,\"text\":\"합성 자막\",\"confidence\":0.8,\"words\":[{\"text\":\"합성\",\"start\":0.1,\"end\":0.5,\"confidence\":0.9,\"speaker\":0}]}"));
                    s=service.status("owner",id);var caption=json.valueToTree(s).path("caption");
                    assertEquals(0.8,caption.path("confidence").asDouble());assertEquals(0,caption.path("words").path(0).path("speaker").asInt(-1));
                    long version=s.version();var ack=service.acknowledge("owner",id,epoch,version,"caption_rendered");
                    var at=json.valueToTree(ack).path("diagnostics").path("captionRenderedAt");assertTrue(at.asLong()>0);assertEquals(version,ack.version());
                    assertEquals(at,json.valueToTree(service.acknowledge("owner",id,epoch,version,"caption_rendered")).path("diagnostics").path("captionRenderedAt"));
                    service.submit("owner",id,epoch,new ConversateQuestionPolicy.Utterance("dg-1","dg-1",1,false,"다음 합성 자막"),"phone_voice",null);
                    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.acknowledge("owner",id,epoch,version,"caption_rendered"));
                    service.submit("owner",id,epoch,new ConversateQuestionPolicy.Utterance("dg-0","dg-0",99,true,"오래된 자막"),"phone_voice",null);
                    assertEquals("dg-1",json.valueToTree(service.status("owner",id)).path("caption").path("utteranceId").asText());
                }finally{output.dispose();}
            }finally{bridge.close();}
        }
    }
    @Test void contextualHintUsesBoundedGeneratorAndDistinctCompletionAndRenderMilestones() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var generator=new ConversateLocalCardGenerator((messages,schema)->{calls.incrementAndGet();return "{\"choice\":3}";},4000);
        try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),new ConversateAnswerPipeline(generator))){
            String owner="a".repeat(64);var s=service.start(owner);String id=s.assistId();long epoch=s.epoch();var output=service.output(owner,id,epoch).subscribe();
            try {
                service.submit(owner,id,epoch,new ConversateQuestionPolicy.Utterance("dg-0","dg-0",1,true,"다음 행동을 결정하기 어렵습니다."),"phone_voice",null);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(service.status(owner,id).card()==null&&System.nanoTime()<deadline)Thread.sleep(10);
                s=service.status(owner,id);assertNotNull(s.card());assertEquals("SUGGESTION",s.card().kind());assertEquals(1,calls.get());assertEquals(0,s.metrics().searchAttempts());
                var d=json.valueToTree(s).path("diagnostics");assertTrue(d.path("hintCompletedAt").asLong()>=d.path("finalTranscriptAt").asLong());assertTrue(d.path("hintRenderedAt").isNull());
                assertEquals(d.path("finalTranscriptAt"),d.path("hintForFinalAt"));
                var rendered=service.acknowledge(owner,id,epoch,s.version(),"rendered");
                assertTrue(json.valueToTree(rendered).path("diagnostics").path("hintRenderedAt").asLong()>0);
                assertEquals("not_observed",rendered.diagnostics().lensVerification());
            }finally{output.dispose();}
        }
    }
    @Test void partialAndFinalReachOwnedSnapshotWithoutGeneratingForPartials() {
        try(var service=new ConversateSessionService()) {
            var s=service.start("caption-owner");
            s=service.submit("caption-owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u1","u1",1,false,"조건을 확인"),"phone_voice",null);
            var first=json.valueToTree(s);
            assertEquals("조건을 확인",first.path("caption").path("text").asText());
            assertFalse(first.path("caption").path("isFinal").asBoolean());
            assertEquals(0,s.metrics().started());
            assertTrue(first.path("diagnostics").path("firstTranscriptAt").asLong()>0);
            s=service.submit("caption-owner",s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u1","u1",2,true,"조건을 확인했습니다."),"phone_voice",null);
            var last=json.valueToTree(s);
            assertTrue(last.path("caption").path("isFinal").asBoolean());
            assertEquals(2,last.path("caption").path("revision").asInt());
            assertEquals(first.path("diagnostics").path("firstTranscriptAt"),last.path("diagnostics").path("firstTranscriptAt"));
            assertTrue(last.path("diagnostics").path("finalTranscriptAt").asLong()>0);
            assertFalse(last.path("diagnostics").toString().contains("조건"));
            String id=s.assistId();long epoch=s.epoch();
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.status("other",id));
            service.control("caption-owner",id,epoch,"pause");
            assertTrue(json.valueToTree(service.status("caption-owner",id)).path("caption").isNull());
        }
    }
    @Test void finalizedUtteranceCannotRegressToPartialAndSameRevisionCanFinalize() {
        var p=new ConversateQuestionPolicy();
        assertEquals("PARTIAL",p.accept(new ConversateQuestionPolicy.Utterance("u","u",1,false,"확인했습니다.")).kind());
        assertEquals("NEW_INFORMATION",p.accept(new ConversateQuestionPolicy.Utterance("u","u",1,true,"확인했습니다.")).kind());
        assertEquals("STALE",p.accept(new ConversateQuestionPolicy.Utterance("u","u",2,false,"확인")).kind());
    }
    @Test void helpSeekingStatementsSelectHintsButNeutralNegatedAndQuotedStatementsDoNot() {
        for(String text:new String[]{"어떤 선택을 할지 잘 모르겠어요.","다음 행동을 결정하기 어렵습니다.","이 조건이 헷갈립니다."})
            assertEquals("HINT",new ConversateQuestionPolicy().accept(new ConversateQuestionPolicy.Utterance("u","u",1,true,text)).kind(),text);
        for(String text:new String[]{"네, 감사합니다.","오늘 회의가 끝났습니다.","도움이 필요하지 않습니다.","이 조건은 안 헷갈려요.","그는 잘 모르겠어요라는 표현을 썼다."})
            assertNotEquals("HINT",new ConversateQuestionPolicy().accept(new ConversateQuestionPolicy.Utterance("u","u",1,true,text)).kind(),text);
    }
}
