package com.example.lms.assist;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DisplayPhonePolicyTest {
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=DisplayConversateHttpTest.JSON;
    static final AtomicInteger launches=new AtomicInteger();
    @BeforeAll static void boot(){
        var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
        context=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties",
            "--server.address=127.0.0.1","--server.port=0","--conversate.enabled=true","--conversate.display.enabled=true",
            "--conversate.display.audio.enabled=true","--conversate.display.sticky-lens=false","--demo.interview.enabled=false",
            "--spring.jackson.deserialization.fail-on-unknown-properties=true","--spring.main.banner-mode=off","--logging.level.root=ERROR");
        DisplayConversateHttpTest.base="http://127.0.0.1:"+context.getWebServer().getPort();
        ReflectionTestUtils.setField(context.getBean(DisplayConversateController.class),"phoneTestEnabled",true);
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void reset(){launches.set(0);}
    static class Phone implements AutoCloseable{
        final DisplayConversateHttpTest.Client client=new DisplayConversateHttpTest.Client();
        final Map<String,Object> connection;
        Phone()throws Exception{
            var hello=client.connection(null,0);hello.put("activate",true);
            var response=client.post("phone-test",hello);assertEquals(200,response.statusCode());
            var view=JSON.readTree(response.body());connection=client.connection(view.path("assistId").asText(),view.path("epoch").asLong());
        }
        Map<String,Object> start(String engine,boolean fallback,List<String> backups){
            var body=new HashMap<>(connection);body.put("sttPolicy",Map.of("engine",engine,"fallbackAllowed",fallback,"allowedFallbacks",backups));return body;
        }
        public void close()throws Exception{client.post("audio/stop",connection);}
    }
    @Test void requestedLocalPolicyReachesCaptureAndItsPublicDiagnosticAllowlist()throws Exception{
        try(var p=new Phone()){
            var response=p.client.post("audio/start",p.start("local",false,List.of()));assertEquals(200,response.statusCode());
            var evidence=JSON.readTree(response.body()).path("testStatus").path("asr");
            assertEquals("local",evidence.path("requestedEngine").asText());assertFalse(evidence.path("fallbackAllowed").asBoolean(true));
            assertEquals("whisper",evidence.path("provider").asText());assertEquals(0,evidence.path("fallbackCount").asInt(-1));
            assertEquals("none",evidence.path("fallbackReason").asText());assertEquals("not_observed",evidence.path("modelEvidence").asText("not_observed"));
            assertEquals(1,launches.get());
        }
    }
    @Test void invalidPolicyRejectsBeforeAnyEngineStarts()throws Exception{
        try(var p=new Phone()){
            for(var body:List.of(p.start("unknown",false,List.of()),p.start("local",true,List.of("soniox")),p.start("soniox",false,List.of("deepgram")))){
                assertEquals(400,p.client.post("audio/start",body).statusCode());assertEquals(0,launches.get());
            }
        }
    }
    @Test void unavailableExplicitCloudCannotSilentlyStartTheConfiguredLocalEngine()throws Exception{
        try(var p=new Phone()){
            var response=p.client.post("audio/start",p.start("soniox",false,List.of()));
            assertEquals(503,response.statusCode());assertEquals("asr_disabled",JSON.readTree(response.body()).path("reason").asText());assertEquals(0,launches.get());
        }
    }
    @Test void legacyStartWithoutPolicyPreservesConfiguredRouteAndOwnerChecks()throws Exception{
        try(var p=new Phone()){
            assertEquals(403,p.client.raw("audio/start",p.start("local",false,List.of()),"https://foreign.invalid",true).statusCode());
            assertEquals(404,new DisplayConversateHttpTest.Client().post("audio/start",p.connection).statusCode());
            assertEquals(200,p.client.post("audio/start",p.connection).statusCode());assertEquals(1,launches.get());
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class Fixture extends DisplayConversateHttpTest.Fixture {
        @Override @Bean ConversateAsrBridge asr(ConversateSessionService sessions,ObjectMapper json){
            return new ConversateAsrBridge(sessions,json,(events,failure)->{
                launches.incrementAndGet();var ready=json.createObjectNode().put("type","ready");
                ready.set("runtime",json.createObjectNode().put("provider","whisper").put("modelEvidence","not_observed"));
                events.accept(ready);
                return new ConversateAsrBridge.Transport(){
                    boolean closed;public void send(String line){}
                    public CompletableFuture<Void> close(){closed=true;return CompletableFuture.completedFuture(null);}
                    public boolean alive(){return !closed;}
                };
            });
        }
    }
}
