package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConversateLocalCardGeneratorTest {
    static final List<ConversateCardPrompt.Evidence> EVIDENCE=List.of(
            new ConversateCardPrompt.Evidence("e1","doc-1","미개봉 제품은 7일 이내 환불이 가능합니다."));
    static String reply(String text,String ids,String negative){
        return "{\"positive\":[{\"kind\":\"FACT\",\"text\":\""+text+"\",\"evidenceIds\":"+ids+"}],\"negative\":"+negative
                +",\"neutral\":{\"decision\":\"SHOW\",\"candidate\":0,\"text\":\""+text+"\",\"evidenceIds\":"+ids+"}}";
    }
    private ConversateLocalCardGenerator generator(String response,AtomicInteger attempts){
        return new ConversateLocalCardGenerator((messages,schema)->{
            attempts.incrementAndGet();assertEquals(2,messages.size());assertFalse(schema.isEmpty());
            assertNull(com.example.lms.service.chat.ChatRunExecutionContext.current());
            assertNotNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get());return response;
        },1000);
    }
    static String compactReply(String text,String ids,String negative) throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=json.readTree(reply(text,ids,negative));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.get("neutral")).remove(List.of("text","evidenceIds"));
        return json.writeValueAsString(root);
    }
    @Test void compactNeutralSelectsOneCanonicalCandidateWithoutDuplicateText() throws Exception {
        var attempts=new AtomicInteger();String text="미개봉 제품은 7일 이내 환불이 가능합니다.";
        var result=generator(compactReply(text,"[\"e1\"]","[]"),attempts).generate("환불 조건은?",EVIDENCE,true,1000);
        assertEquals("GENERATED",result.reason());assertEquals(text,result.card().text());assertEquals(List.of("doc-1"),result.card().sourceIds());assertEquals(1,attempts.get());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var schema=json.valueToTree(ConversateCardPrompt.build("환불 조건은?",EVIDENCE,true).schema());
        var neutral=schema.path("properties").path("neutral").path("properties");
        assertEquals(2,neutral.size());assertTrue(neutral.has("decision"));assertTrue(neutral.has("candidate"));
        String exchange="미개봉 제품은 7일 이내 교환이 가능합니다.";
        var choice=json.readTree(compactReply(text,"[\"e1\"]","[]"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)choice.get("positive")).add(json.valueToTree(Map.of("kind","FACT","text",exchange,"evidenceIds",List.of("e2"))));
        ((com.fasterxml.jackson.databind.node.ObjectNode)choice.get("neutral")).put("candidate",1);
        var chosen=generator(json.writeValueAsString(choice),new AtomicInteger()).generate("교환 조건은?",List.of(EVIDENCE.get(0),new ConversateCardPrompt.Evidence("e2","doc-2",exchange)),true,1000);
        assertEquals("GENERATED",chosen.reason());assertEquals(exchange,chosen.card().text());assertEquals(List.of("doc-2"),chosen.card().sourceIds());
    }
    @Test void compactSelectionPreservesFactGuardsAndLegacyAgreement() throws Exception {
        var cases=Map.of("미개봉 제품은 8일 이내 환불이 가능합니다.","QUANTITY_REJECTED","미개봉 제품은 7일 이내 환불이 불가능합니다.","NEGATION_REJECTED","제품은 7일 이내 환불이 가능합니다.","CONDITION_REJECTED","미개봉 제품은 7일 이내 환불과 무료배송이 가능합니다.","UNSUPPORTED_WORDING");
        for(var c:cases.entrySet())assertEquals(c.getValue(),generator(compactReply(c.getKey(),"[\"e1\"]","[]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
        String text="미개봉 제품은 7일 이내 환불이 가능합니다.";
        for(String index:List.of("-1","2","4294967296"))assertEquals("PARSE_FAILED",generator(compactReply(text,"[\"e1\"]","[]").replace("\"candidate\":0","\"candidate\":"+index),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason(),"candidate="+index);
        assertEquals("SOURCE_REJECTED",generator(compactReply(text,"[\"invented\"]","[]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
        assertEquals("EVIDENCE_CONFLICT",generator(compactReply(text,"[\"e1\"]","[{\"evidenceId\":\"e1\",\"issue\":\"CONDITION\"}]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var legacy=json.readTree(reply(text,"[\"e1\"]","[]"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy.get("neutral")).put("text","제품은 7일 이내 환불이 가능합니다.");
        assertEquals("SOURCE_REJECTED",generator(json.writeValueAsString(legacy),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
    }
    @Test void complexCardUsesOneStructuredLocalCallAndActualRetrievedSourceIds(){
        var attempts=new AtomicInteger();
        var result=generator(reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"e1\"]","[]"),attempts)
                .generate("환불 조건은 무엇인가요?",EVIDENCE,true,1000);
        assertEquals("GENERATED",result.reason());assertEquals("SHOW",result.card().decision());
        assertEquals(List.of("doc-1"),result.card().sourceIds());assertEquals(1,attempts.get());
        assertEquals(1,result.attempts());assertEquals(1,result.complexJudgments());
        assertNull(com.abandonware.ai.addons.budget.TimeBudgetContext.get());
    }
    @Test void inventedSourceAmountUnitNegationConditionAndNewFactAreRejected(){
        var cases=Map.of(
                "미개봉 제품은 8일 이내 환불이 가능합니다.","QUANTITY_REJECTED",
                "미개봉 제품은 7개월 이내 환불이 가능합니다.","QUANTITY_REJECTED",
                "미개봉 제품은 7일 이내 환불이 불가능합니다.","NEGATION_REJECTED",
                "제품은 7일 이내 환불이 가능합니다.","CONDITION_REJECTED",
                "미개봉 제품은 7일 이내 환불과 무료배송이 가능합니다.","UNSUPPORTED_WORDING");
        for(var entry:cases.entrySet()){
            var result=generator(reply(entry.getKey(),"[\"e1\"]","[]"),new AtomicInteger()).generate("환불 조건은?",EVIDENCE,true,1000);
            assertEquals(entry.getValue(),result.reason());assertNotEquals("SHOW",result.card().decision());assertTrue(result.card().sourceIds().isEmpty());
        }
        assertEquals("SOURCE_REJECTED",generator(reply("환불 가능합니다.","[\"invented\"]","[]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
    }
    @Test void counterexampleCannotBeIgnoredAndPartialMalformedOrExcessiveOutputIsNotDisplayed(){
        assertEquals("EVIDENCE_CONFLICT",generator(reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"e1\"]","[{\"evidenceId\":\"e1\",\"issue\":\"CONDITION\"}]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
        for(String invalid:List.of("not json","{}",reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"e1\"]","[]")+" {}","{\"positive\":[],\"positive\":[]}"))
            assertEquals("PARSE_FAILED",generator(invalid,new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
        assertEquals("CARD_LIMIT",generator(reply("가".repeat(ConversateSessionService.HINT_TEXT_MAX+1),"[\"e1\"]","[]"),new AtomicInteger()).generate("환불은?",EVIDENCE,true,1000).reason());
    }
    @Test void fieldTestedLengthCardGeneratesWithinTheHardCap() throws Exception {
        String text="미개봉 제품은 기본 환불 규정과 상태 확인이 필요합니다 ".repeat(16).strip();
        assertTrue(text.codePointCount(0,text.length())>=480&&text.codePointCount(0,text.length())<=540);
        var evidence=List.of(new ConversateCardPrompt.Evidence("e1","doc-1",text));
        var result=generator(reply(text,"[\"e1\"]","[]"),new AtomicInteger()).generate("환불 조건은?",evidence,true,1000);
        assertEquals("GENERATED",result.reason());assertEquals(text,result.card().text());
    }
    @Test void disabledAndNonLoopbackConfigurationNeverStartAnInference(){
        var disabled=new ConversateLocalCardGenerator(false,"","",1000);
        assertEquals("GENERATION_DISABLED",disabled.generate("환불은?",EVIDENCE,true,1000).reason());
        var external=new ConversateLocalCardGenerator(true,"https://example.test/v1","qwen3:fixture",1000);
        var result=external.generate("환불은?",EVIDENCE,true,1000);
        assertEquals("LOCAL_ROUTE_REJECTED",result.reason());assertEquals(0,result.attempts());
    }
    @Test void callerBudgetIsRestoredAndInterruptedWorkNeverCallsModel(){
        var previous=new com.abandonware.ai.addons.budget.TimeBudget(5000);com.abandonware.ai.addons.budget.TimeBudgetContext.set(previous);
        var attempts=new AtomicInteger();var generator=generator("{}",attempts);
        try {
            generator.generate("환불은?",EVIDENCE,true,1000);assertSame(previous,com.abandonware.ai.addons.budget.TimeBudgetContext.get());
            Thread.currentThread().interrupt();
            assertEquals("CANCELLED",generator.generate("환불은?",EVIDENCE,true,1000).reason());assertEquals(1,attempts.get());
        } finally {Thread.interrupted();com.abandonware.ai.addons.budget.TimeBudgetContext.clear();}
    }
    @Test void longPreparedEvidenceUsesGeneratorButSimpleNoMatchAndConflictDoNot(){
        var calls=new AtomicInteger();
        var pipeline=new ConversateAnswerPipeline(generator(reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"s0\"]","[]"),calls));
        String longText="고객에게 안내하는 제품 환불 절차에 대한 자세한 설명을 확인하고 관련된 내용을 상담 직원과 함께 검토할 수 있으며 미개봉 제품은 7일 이내 환불이 가능합니다.";
        var result=pipeline.answer("환불 조건은 무엇인가요?",List.of(new PreparedMaterialReader.Material("doc-1",longText)),1000);
        assertEquals("GENERATED",result.reason());assertEquals(1,result.generationAttempts());assertEquals(List.of("doc-1"),result.card().sourceIds());
        assertEquals("MATCH",pipeline.answer("환불은 되나요?",List.of(new PreparedMaterialReader.Material("doc-1",EVIDENCE.get(0).text())),1000).reason());
        assertEquals("NO_MATCH",pipeline.answer("배송 날짜는?",List.of(new PreparedMaterialReader.Material("doc-1",longText)),1000).reason());
        assertEquals("EVIDENCE_CONFLICT",pipeline.answer("가입 비용은 얼마인가요?",List.of(new PreparedMaterialReader.Material("a","가입 비용은 30만원입니다."),new PreparedMaterialReader.Material("b","가입 비용은 50만원입니다.")),1000).reason());
        assertEquals(1,calls.get());
    }
    @Test void nativeStructuredHttpUsesOneAttemptAndSeparatesUpstreamFailures() throws Exception {
        var status=new AtomicInteger(200);var calls=new AtomicInteger();var request=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{calls.incrementAndGet();request.set(json.readTree(exchange.getRequestBody().readAllBytes()));byte[] body=(status.get()==200?json.writeValueAsString(Map.of("message",Map.of("content",reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"e1\"]","[]")))):"{\"error\":\"fixture capacity\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            var generator=new ConversateLocalCardGenerator(true,"http://127.0.0.1:"+server.getAddress().getPort(),"qwen3:fixture",4000);
            var first=generator.generate("환불 조건은?",EVIDENCE,true,1000);assertEquals("GENERATED",first.reason());assertEquals(1,calls.get());
            assertEquals("object",request.get().path("format").path("type").asText());assertFalse(request.get().path("think").asBoolean(true));assertFalse(request.get().path("stream").asBoolean(true));assertEquals(512,request.get().path("options").path("num_predict").asInt());assertFalse(request.get().path("options").has("num_gpu"));
            for(var entry:Map.of(404,"MODEL_NOT_FOUND",429,"GENERATION_RATE_LIMITED",403,"GENERATION_DENIED",500,"GENERATION_UNAVAILABLE").entrySet()){
                status.set(entry.getKey());int before=calls.get();assertEquals(entry.getValue(),generator.generate("환불은?",EVIDENCE,true,1000).reason());assertEquals(before+1,calls.get());
            }
        } finally {server.stop(0);}
    }
    @Test void plainAssistInterruptClosesHeldNativeSocketWithoutChatRunContext() throws Exception {
        var received=new java.util.concurrent.CountDownLatch(1);var closed=new java.util.concurrent.CountDownLatch(1);var done=new java.util.concurrent.CountDownLatch(1);
        var result=new java.util.concurrent.atomic.AtomicReference<ConversateLocalCardGenerator.Result>();
        try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);var receiver=java.util.concurrent.Executors.newSingleThreadExecutor();
            var peer=receiver.submit(()->{try(var socket=server.accept()){socket.setSoTimeout(5000);var input=socket.getInputStream();var header=new StringBuilder();while(!header.toString().endsWith("\r\n\r\n")&&header.length()<16384){int b=input.read();if(b<0)throw new java.io.IOException();header.append((char)b);}var length=java.util.regex.Pattern.compile("(?im)^content-length: *([0-9]+)").matcher(header);if(length.find())input.readNBytes(Integer.parseInt(length.group(1)));received.countDown();try{if(input.read()==-1)closed.countDown();}catch(java.net.SocketException reset){closed.countDown();}}catch(Exception failure){throw new RuntimeException("fixture_socket_failed");}});
            var generator=new ConversateLocalCardGenerator(true,"http://127.0.0.1:"+server.getLocalPort(),"qwen3:fixture",4000);
            var worker=new Thread(()->{try{result.set(generator.generate("환불은?",EVIDENCE,true,1000));}finally{Thread.interrupted();done.countDown();}},"assist-native-cancel-fixture");
            try {worker.start();assertTrue(received.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,done.getCount());long began=System.nanoTime();worker.interrupt();assertTrue(done.await(2,java.util.concurrent.TimeUnit.SECONDS));long remaining=2_000_000_000L-(System.nanoTime()-began);assertTrue(closed.await(Math.max(1,remaining),java.util.concurrent.TimeUnit.NANOSECONDS));assertEquals("CANCELLED",result.get().reason());assertEquals(1,result.get().attempts());peer.get(1,java.util.concurrent.TimeUnit.SECONDS);System.out.println("assistNativeSocketClosedMs="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));}
            finally{worker.interrupt();worker.join(5000);receiver.shutdownNow();receiver.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
        }
    }
    @Test void lateGeneratorResultAfterPauseIsDiscardedAndDoesNotReleaseCapacityEarly() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);
        var generator=new ConversateLocalCardGenerator((messages,schema)->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}finished.countDown();return reply("미개봉 제품은 7일 이내 환불이 가능합니다.","[\"s0\"]","[]");},4000);
        String owner="a".repeat(64);
        try(var service=new ConversateSessionService(java.time.Clock.systemUTC(),new ConversateAnswerPipeline(generator))){
            var s=service.start(owner);s=service.prepare(owner,s.assistId(),s.epoch(),List.of(new PreparedMaterialReader.Material("doc","자세한 환불 설명과 상담 관련 안내를 충분히 읽고 검토한 다음 원하는 제품과 해당 조건을 확인할 수 있으며 미개봉 제품은 7일 이내 환불이 가능합니다.")));
            service.submit(owner,s.assistId(),s.epoch(),new ConversateQuestionPolicy.Utterance("u1","q1",1,true,"환불 조건은 무엇인가요?"));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var paused=service.control(owner,s.assistId(),s.epoch(),"pause");assertNull(paused.card());assertEquals(1,paused.metrics().inFlight());release.countDown();assertTrue(finished.await(2,java.util.concurrent.TimeUnit.SECONDS));
            long deadline=System.nanoTime()+2_000_000_000L;while(service.status(owner,s.assistId()).metrics().inFlight()>0&&System.nanoTime()<deadline)Thread.sleep(5);
            var finalState=service.status(owner,s.assistId());assertEquals(0,finalState.metrics().inFlight());assertNull(finalState.card());assertEquals("PAUSED",finalState.state());assertEquals(1,finalState.metrics().generationAttempts());assertEquals(1,finalState.metrics().dropped());
        }finally{release.countDown();}
    }
}
