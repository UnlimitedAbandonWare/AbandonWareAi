package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DisplayAudioBatchTest {
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=DisplayConversateHttpTest.JSON;
    static final List<Long> sent=new CopyOnWriteArrayList<>();
    static volatile long failSequence=-1;
    @BeforeAll static void boot(){
        var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
        context=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties",
            "--server.address=127.0.0.1","--server.port=0","--conversate.enabled=true","--conversate.display.enabled=true",
            "--conversate.display.audio.enabled=true","--conversate.display.sticky-lens=false","--demo.interview.enabled=false",
            "--spring.jackson.deserialization.fail-on-unknown-properties=true","--spring.main.banner-mode=off","--logging.level.root=ERROR");
        DisplayConversateHttpTest.base="http://127.0.0.1:"+context.getWebServer().getPort();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void reset(){sent.clear();failSequence=-1;}
    static List<Map<String,Object>> frames(int count){
        var list=new ArrayList<Map<String,Object>>();String pcm=Base64.getEncoder().encodeToString(new byte[7680]);
        for(int i=0;i<count;i++)list.add(Map.of("sequence",i,"pcm",pcm));return list;
    }
    static class Capture implements AutoCloseable{
        final DisplayConversateHttpTest.Client client=new DisplayConversateHttpTest.Client();
        final Map<String,Object> connection;
        Capture()throws Exception{var view=client.bootstrap();connection=client.connection(view.path("assistId").asText(),view.path("epoch").asLong());assertEquals(200,client.post("audio/start",connection).statusCode());}
        Map<String,Object> batch(List<Map<String,Object>> frames){var body=new HashMap<>(connection);body.put("frames",frames);return body;}
        public void close()throws Exception{client.post("audio/stop",connection);}
    }
    @Test void eightFullFramesCrossHttpGuardInOrderAndReturnExactAck()throws Exception{
        try(var c=new Capture()){
            var response=c.client.post("audio/chunk-batch",c.batch(frames(8)));assertEquals(200,response.statusCode());
            var ack=JSON.readTree(response.body());assertEquals(8,ack.path("acceptedCount").asInt());assertEquals(7,ack.path("acceptedThrough").asLong());
            assertTrue(ack.path("view").path("ready").asBoolean());assertEquals(List.of(0L,1L,2L,3L,4L,5L,6L,7L),sent);
            assertFalse(response.body().contains("\"pcm\""));
        }
    }
    @Test void shapeAndSequenceValidationRejectWholeBatchBeforeFirstWrite()throws Exception{
        try(var c=new Capture()){
            for(var invalid:List.of(frames(9),List.<Map<String,Object>>of(),
                    List.of(Map.<String,Object>of("sequence",0,"pcm","AAA="),Map.<String,Object>of("sequence",1,"pcm","!")),
                    List.of(frames(1).get(0),Map.<String,Object>of("sequence",2,"pcm",frames(1).get(0).get("pcm"))))){
                assertEquals(400,c.client.post("audio/chunk-batch",c.batch(invalid)).statusCode());assertTrue(sent.isEmpty());
            }
        }
    }
    @Test void rateAdmissionCountsFramesAndRejectsBeforeAnyPrefixIsSent()throws Exception{
        try(var c=new Capture()){
            var controller=context.getBean(DisplayConversateController.class);
            var bindings=(Map<?,?>)ReflectionTestUtils.getField(controller,"bindings");
            var binding=bindings.values().stream().filter(b->c.connection.get("assistId").equals(ReflectionTestUtils.getField(b,"id"))).findFirst().orElseThrow();
            var window=ReflectionTestUtils.getField(binding,"window");
            var counts=(int[])ReflectionTestUtils.getField(window,"counts");counts[4]=359;
            var response=c.client.post("audio/chunk-batch",c.batch(frames(8)));
            assertEquals(429,response.statusCode());assertTrue(sent.isEmpty());assertEquals(359,counts[4]);
        }
    }
    @Test void failedSecondFrameCannotReturnSuccessAckForTheBatch()throws Exception{
        try(var c=new Capture()){
            failSequence=1;var response=c.client.post("audio/chunk-batch",c.batch(frames(3)));
            assertEquals(503,response.statusCode());assertEquals(List.of(0L),sent);assertFalse(JSON.readTree(response.body()).has("acceptedThrough"));
        }finally{failSequence=-1;}
    }
    @Test void duplicateAckDoesNotResendAndOldEpochCannotWrite()throws Exception{
        try(var c=new Capture()){
            var body=c.batch(frames(2));assertEquals(200,c.client.post("audio/chunk-batch",body).statusCode());
            assertEquals(200,c.client.post("audio/chunk-batch",body).statusCode());assertEquals(List.of(0L,1L),sent);
            body.put("epoch",((Number)c.connection.get("epoch")).longValue()+1);
            assertEquals(409,c.client.post("audio/chunk-batch",body).statusCode());assertEquals(List.of(0L,1L),sent);
        }
    }
    @Test void bodyExceptionIsExactAndRetainsOriginOwnerAndGenericInputLimits()throws Exception{
        try(var c=new Capture()){
            var body=c.batch(frames(1));
            assertEquals(403,c.client.raw("audio/chunk-batch",body,"https://foreign.invalid",true).statusCode());
            assertEquals(403,c.client.raw("audio/chunk-batch",body,DisplayConversateHttpTest.base,false).statusCode());
            assertEquals(404,new DisplayConversateHttpTest.Client().post("audio/chunk-batch",body).statusCode());
            var tooBig=new HashMap<>(c.connection);tooBig.put("text","x".repeat(17000));
            assertEquals(413,c.client.post("input",tooBig).statusCode());
            tooBig.put("text","x".repeat(100000));assertEquals(413,c.client.post("audio/chunk-batch",tooBig).statusCode());
            byte[] bytes=JSON.writeValueAsBytes(tooBig);
            var chunked=HttpRequest.newBuilder(URI.create(DisplayConversateHttpTest.base+"/api/assist/display/audio/chunk-batch"))
                .header("Origin",DisplayConversateHttpTest.base).header("X-Display-Client","1").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(bytes))).build();
            assertEquals(413,c.client.http.send(chunked,HttpResponse.BodyHandlers.discarding()).statusCode());assertTrue(sent.isEmpty());
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class Fixture extends DisplayConversateHttpTest.Fixture {
        @Override @Bean ConversateAsrBridge asr(ConversateSessionService sessions,ObjectMapper json){
            return new ConversateAsrBridge(sessions,json,(events,failure)->{
                events.accept(json.createObjectNode().put("type","ready"));
                return new ConversateAsrBridge.Transport(){
                    boolean closed;
                    public void send(String line)throws java.io.IOException{
                        long sequence=json.readTree(line).path("seq").asLong();
                        if(sequence==failSequence)throw new java.io.IOException("synthetic_transport_failure");
                        sent.add(sequence);events.accept(json.createObjectNode().put("type","ack").put("seq",sequence));
                    }
                    public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}
                    public boolean alive(){return !closed;}
                };
            });
        }
    }
}

