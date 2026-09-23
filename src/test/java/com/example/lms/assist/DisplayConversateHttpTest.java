package com.example.lms.assist;

import com.example.lms.service.ChatService;
import com.example.lms.service.ChatResult;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.web.*;
import com.example.lms.security.ChatOpenSecurityConfig;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayConversateHttpTest {
    static ServletWebServerApplicationContext context;static String base;static final ObjectMapper JSON=new ObjectMapper();static final AtomicInteger calls=new AtomicInteger();static final java.util.concurrent.atomic.AtomicBoolean asrFails=new java.util.concurrent.atomic.AtomicBoolean();
    static final String SYNTHETIC_FINAL="지금 회의에서는 RAG 검색 품질과 안경 렌즈에 보이는 전사 줄 수를 어떻게 조정할지 이야기하고 있습니다. 방금 나온 질문과 다음 결정 사항, 그리고 렌즈에 표시할 문장을 빠짐없이 정리해 주시면 감사하겠습니다.";
    @BeforeAll static void boot(){
        var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
        context=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties","--server.address=127.0.0.1","--server.port=0","--conversate.enabled=true","--conversate.display.enabled=true","--conversate.display.audio.enabled=true","--conversate.display.sticky-lens=false","--demo.interview.enabled=false","--spring.jackson.deserialization.fail-on-unknown-properties=true","--spring.main.banner-mode=off","--logging.level.root=ERROR");
        base="http://127.0.0.1:"+context.getWebServer().getPort();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @Test void runtimeDiagnosticsRetainSameOriginBoundaryAndCannotGenerateHints() throws Exception {
        var c=new Client();var body=Map.of("runtimeId","a".repeat(32),"event","init_started","sequence",0,"code","none","visible",true);int before=calls.get();
        assertEquals(403,c.raw("relay/diagnostics",body,"https://other.example",true).statusCode());
        assertEquals(403,c.raw("relay/diagnostics",body,base,false).statusCode());
        assertEquals(204,c.post("relay/diagnostics",body).statusCode());assertEquals(400,c.post("relay/diagnostics",Map.of("runtimeId","a".repeat(32))).statusCode());assertEquals(before,calls.get());
    }
    @Test void externalLauncherDoesNotWaitForDisabledManagerProbeAndManagedStartupStillWaits() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);
        var manager=mock(com.example.lms.config.LocalLlmProcessManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",manager);
        try{
            when(manager.diagnostics()).thenReturn(Map.of("enabled",true,"autostart",false,"serviceResponding",false,"modelPresent",false));
            assertTrue(new Client().bootstrap().path("ready").asBoolean());
            when(manager.diagnostics()).thenReturn(Map.of("enabled",true,"autostart",true,"serviceResponding",false,"modelPresent",false));
            var c=new Client();assertEquals(503,c.post("bootstrap",c.connection(null,0)).statusCode());
            when(manager.diagnostics()).thenReturn(Map.of("enabled",true,"autostart",true,"serviceResponding",true,"modelPresent",true));
            assertTrue(c.bootstrap().path("ready").asBoolean());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",null);}
    }
    @Test void anonymousBootstrapInputPollAndReloadUseOneIsolatedSession() throws Exception {
        var client=new Client();var s=client.bootstrap();String id=s.path("assistId").asText();long epoch=s.path("epoch").asLong();
        assertTrue(s.path("ready").asBoolean());assertFalse(s.has("audio"));assertFalse(s.has("csrfToken"));
        assertEquals(id,client.bootstrap().path("assistId").asText());
        int before=calls.get();String req=UUID.randomUUID().toString();var body=client.connection(id,epoch);body.put("requestId",req);body.put("text","짧게 요약해 줘");body.put("eventOrder",List.of("focus","activate","input","change"));
        assertEquals(200,client.post("input",body).statusCode());var dup=client.post("input",body);assertEquals(200,dup.statusCode());assertFalse(JSON.readTree(dup.body()).has("duplicates"));
        long end=System.nanoTime()+3_000_000_000L;JsonNode output;
        do{output=JSON.readTree(client.post("poll",client.connection(id,epoch)).body());if(output.path("card").isObject())break;Thread.sleep(20);}while(System.nanoTime()<end);
        assertEquals("짧은 공개 답변",output.path("card").path("text").asText());assertEquals(before+1,calls.get());
        assertEquals(404,new Client().post("poll",client.connection(id,epoch)).statusCode());
        assertEquals(id,client.bootstrap().path("assistId").asText());assertEquals(before+1,calls.get());
    }
    @Test void publicRoutesRejectCrossOriginMissingHeaderAndProtectedAssistRoutes() throws Exception {
        var client=new Client();
        for(String path:List.of("/api/assist/bootstrap","/api/assist/materials","/conversate","/api/admin/settings")){
            int status=client.http.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(status==401||status==403);
        }
        for(String origin:List.of("https://other.example","null"))assertEquals(403,client.raw("bootstrap",client.connection(null,0),origin,true).statusCode());
        assertEquals(403,client.raw("bootstrap",client.connection(null,0),base,false).statusCode());
        assertEquals(403,client.http.send(HttpRequest.newBuilder(URI.create(base+"/api/assist/sessions")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test void directVerificationRejectsForwardingAndSecondLogicalRequestWithoutLegacyGeneration() throws Exception {
        var client=new Client();var s=client.bootstrap();var body=client.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        body.put("requestId",UUID.randomUUID().toString());body.put("text","합성 질문");body.put("eventOrder",List.of("submit"));body.put("verificationMode","openai-direct");
        var forwarded=HttpRequest.newBuilder(URI.create(base+"/api/assist/display/input")).header("Content-Type","application/json")
                .header("X-Display-Client","1").header("Origin",base).header("X-Forwarded-For","192.0.2.1")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        assertEquals(403,client.http.send(forwarded,HttpResponse.BodyHandlers.discarding()).statusCode());
        int before=calls.get();assertEquals(200,client.post("input",body).statusCode());
        body.put("requestId",UUID.randomUUID().toString());assertEquals(409,client.post("input",body).statusCode());
        assertEquals(before,calls.get());
    }
    @Test void displayModeBlocksLegacyChatAndSocketBeforeTheirHandlers() throws Exception {
        var client=new Client();int before=LegacyProbe.calls.get();
        for(String path:List.of("/api/chat/sync","/api/chat/history","/ws/chat")){
            var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
            if(path.endsWith("sync"))request.POST(HttpRequest.BodyPublishers.ofString("{}"));else request.GET();
            assertEquals(403,client.http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        assertEquals(before,LegacyProbe.calls.get());assertTrue(client.bootstrap().path("ready").asBoolean());
    }
    @Test void rateWindowBoundsEvenDuplicateInputsAndOtherOwnersRemainIndependent() throws Exception {
        var client=new Client();var s=client.bootstrap();var body=client.connection(s.path("assistId").asText(),s.path("epoch").asLong());body.put("requestId",UUID.randomUUID().toString());body.put("text","요약해 줘");body.put("eventOrder",List.of("change"));
        for(int i=0;i<6;i++)assertEquals(200,client.post("input",body).statusCode());
        var limited=client.post("input",body);assertEquals(429,limited.statusCode());assertTrue(Integer.parseInt(limited.headers().firstValue("Retry-After").orElse("0"))>0);
        assertTrue(new Client().bootstrap().path("ready").asBoolean());
    }
    @Test void rejectsBlankOversizedAndUntrustedEventPayload() throws Exception {
        var c=new Client();var s=c.bootstrap();var b=c.connection(s.path("assistId").asText(),s.path("epoch").asLong());b.put("requestId",UUID.randomUUID().toString());b.put("eventOrder",List.of("change"));b.put("text"," ");assertEquals(400,c.post("input",b).statusCode());
        b.put("text","x".repeat(2001));assertEquals(400,c.post("input",b).statusCode());b.put("text","질문");b.put("eventOrder",List.of("PRIVATE_VALUE"));assertEquals(400,c.post("input",b).statusCode());
        b.put("text","x".repeat(17000));assertEquals(413,c.post("input",b).statusCode());
    }
    @Test void explicitAnonymousAudioUsesExistingBridgeFinalOnlyCardsAndStopsToText() throws Exception {
        var c=new Client();var s=c.bootstrap();assertTrue(s.path("audioAvailable").asBoolean());
        var b=c.connection(s.path("assistId").asText(),s.path("epoch").asLong());int before=calls.get();
        assertEquals(200,c.post("audio/start",b).statusCode());assertEquals(before,calls.get());
        var chunk=new HashMap<>(b);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));
        var sent=c.post("audio/chunk",chunk);assertEquals(200,sent.statusCode());assertFalse(JSON.readTree(sent.body()).has("pcm"));
        JsonNode output=null;long end=System.nanoTime()+3_000_000_000L;
        do{output=JSON.readTree(c.post("poll",b).body());if(output.path("card").isObject())break;Thread.sleep(20);}while(System.nanoTime()<end);
        assertEquals(before+1,calls.get());assertEquals(output.path("voiceRequestId"),output.path("card").path("requestId"));
        assertEquals(200,c.post("audio/chunk",chunk).statusCode());assertEquals(before+1,calls.get());
        assertEquals(404,new Client().post("audio/chunk",chunk).statusCode());
        chunk.put("sequence",2);assertEquals(409,c.post("audio/chunk",chunk).statusCode());
        chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[1280]));assertEquals(409,c.post("audio/chunk",chunk).statusCode());
        chunk.put("sequence",1);chunk.put("pcm","A".repeat(10244));assertEquals(400,c.post("audio/chunk",chunk).statusCode());
        var stopped=JSON.readTree(c.post("audio/stop",b).body());assertTrue(stopped.path("ready").asBoolean());assertTrue(stopped.path("epoch").asLong()>s.path("epoch").asLong());
        assertEquals(409,c.post("audio/chunk",b).statusCode());assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
    }
    @Test void displayAudioRetainsOptInOriginAndLegacyNegativeBoundaries() throws Exception {
        var c=new Client();var s=c.bootstrap();var b=c.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        for(String route:List.of("audio/start","audio/chunk","audio/stop")){
            assertEquals(403,c.raw(route,b,"https://other.example",true).statusCode());assertEquals(403,c.raw(route,b,"null",true).statusCode());assertEquals(403,c.raw(route,b,base,false).statusCode());
        }
        var controller=context.getBean(DisplayConversateController.class);var security=context.getBean(ChatOpenSecurityConfig.class);
        try{org.springframework.test.util.ReflectionTestUtils.setField(controller,"audioEnabled",false);org.springframework.test.util.ReflectionTestUtils.setField(security,"displayAudio",false);
            assertFalse(c.bootstrap().path("audioAvailable").asBoolean());assertEquals(403,c.post("audio/start",b).statusCode());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"audioEnabled",true);org.springframework.test.util.ReflectionTestUtils.setField(security,"displayAudio",true);}
        assertEquals(403,c.http.send(HttpRequest.newBuilder(URI.create(base+"/api/assist/sessions/"+s.path("assistId").asText()+"/audio/start")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @Test void workletIsServedWithoutLoginAndDefaultInterviewModeKeepsExplicitDisplayRoutes() throws Exception {
        var c=new Client();var asset=c.http.send(HttpRequest.newBuilder(URI.create(base+"/assets/display/pcm-worklet.js")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,asset.statusCode());assertTrue(asset.body().contains("registerProcessor('conversate-pcm'"));
        var app=new SpringApplication(Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
        try(var demo=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties","--server.address=127.0.0.1","--server.port=0","--conversate.enabled=true","--conversate.display.enabled=true","--conversate.display.audio.enabled=true","--demo.interview.enabled=true","--spring.main.banner-mode=off","--logging.level.root=ERROR")){
            String origin="http://127.0.0.1:"+demo.getWebServer().getPort();var h=HttpClient.newHttpClient();
            var request=HttpRequest.newBuilder(URI.create(origin+"/api/assist/display/bootstrap")).header("Origin",origin).header("X-Display-Client","1").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(c.connection(null,0)))).build();
            var result=h.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode());assertTrue(JSON.readTree(result.body()).path("audioAvailable").asBoolean());
            for(String path:List.of("/login","/register","/api/admin/settings"))assertEquals(404,h.send(HttpRequest.newBuilder(URI.create(origin+path)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200,h.send(HttpRequest.newBuilder(URI.create(origin+"/assets/display/pcm-worklet.js")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void failedAudioStartupReturnsToUsableTextOnBootstrapWithoutReplayingAudio() throws Exception {
        var c=new Client();var s=c.bootstrap();var b=c.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        try{asrFails.set(true);assertEquals(503,c.post("audio/start",b).statusCode());}
        finally{asrFails.set(false);}
        var recovered=c.bootstrap();assertTrue(recovered.path("ready").asBoolean());assertEquals(s.path("assistId"),recovered.path("assistId"));assertEquals(s.path("epoch"),recovered.path("epoch"));assertEquals("WAITING",recovered.path("audioState").asText());assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
        var input=c.connection(recovered.path("assistId").asText(),recovered.path("epoch").asLong());input.put("requestId",UUID.randomUUID().toString());input.put("text","RAG가 무엇인가요?");input.put("eventOrder",List.of("change"));assertEquals(200,c.post("input",input).statusCode());
        long end=System.nanoTime()+3_000_000_000L;JsonNode answer;
        do{var response=c.post("poll",c.connection(recovered.path("assistId").asText(),recovered.path("epoch").asLong()));assertEquals(200,response.statusCode());answer=JSON.readTree(response.body());if(answer.path("card").isObject())break;Thread.sleep(20);}while(System.nanoTime()<end);
        assertTrue(answer.path("card").isObject(),answer.path("reason").asText()+":"+answer.path("processing").asBoolean());assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
    }
    @Test void firstProviderFailureCanResumeTheSameSessionWithoutReplayingAudio() throws Exception {
        var c=new Client();var original=c.bootstrap();
        var connection=c.connection(original.path("assistId").asText(),original.path("epoch").asLong());
        try{asrFails.set(true);assertEquals(503,c.post("audio/start",connection).statusCode());}
        finally{asrFails.set(false);}
        var waiting=c.bootstrap();assertEquals("WAITING",waiting.path("audioState").asText());
        assertEquals(original.path("assistId"),waiting.path("assistId"));assertEquals(original.path("epoch"),waiting.path("epoch"));
        connection.put("continuation",true);
        var response=c.post("audio/start",connection);assertEquals(200,response.statusCode());
        var resumed=JSON.readTree(response.body());assertEquals(original.path("assistId"),resumed.path("assistId"));
        assertTrue(resumed.path("epoch").asLong()>original.path("epoch").asLong());assertTrue(resumed.path("ready").asBoolean());
        var chunk=c.connection(resumed.path("assistId").asText(),resumed.path("epoch").asLong());
        chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[7680]));
        assertEquals(200,c.post("audio/chunk",chunk).statusCode());
    }
    @Test void phonePairingNeedsReceiverApprovalAndCaptionsAreIndependentOfHints() throws Exception {
        var display=new Client();var phone=new Client();var stranger=new Client();
        var init=display.post("transcription",display.connection(null,0));assertEquals(200,init.statusCode());
        var s=JSON.readTree(init.body());var bound=display.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        assertFalse(s.path("hintsEnabled").asBoolean());
        var code=JSON.readTree(display.post("link/code",bound).body()).path("code").asText();assertTrue(code.matches("[0-9]{6}"));
        var join=new HashMap<String,Object>();join.put("clientId",phone.client);join.put("code",code);join.put("requestId",UUID.randomUUID().toString());
        assertEquals(200,phone.post("link/join",join).statusCode());assertEquals(200,phone.post("link/join",join).statusCode());
        assertEquals(409,phone.post("transcription",phone.connection(null,0)).statusCode());
        assertEquals(404,phone.post("audio/start",bound).statusCode());
        assertEquals(404,stranger.post("link/join",join).statusCode());
        assertEquals(200,display.post("link/approve",bound).statusCode());
        var paired=JSON.readTree(phone.post("transcription",phone.connection(null,0)).body());
        assertEquals(s.path("assistId"),paired.path("assistId"));assertEquals("PHONE",paired.path("role").asText());
        var input=phone.connection(paired.path("assistId").asText(),paired.path("epoch").asLong());
        assertEquals(404,stranger.post("poll",input).statusCode());assertEquals(403,display.post("audio/start",bound).statusCode());
        int before=calls.get();assertEquals(200,phone.post("audio/start",input).statusCode());
        var chunk=new HashMap<>(input);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[7680]));
        assertEquals(200,phone.post("audio/chunk",chunk).statusCode());
        var caption=JSON.readTree(display.post("poll",bound).body());assertTrue(caption.path("caption").path("isFinal").asBoolean());
        assertEquals(SYNTHETIC_FINAL,caption.path("caption").path("text").asText());assertTrue(caption.path("captionTtlMs").asLong()>0);
        assertEquals(before,calls.get());assertTrue(caption.path("card").isNull());assertFalse(caption.has("audioChunks"));
        var ack=new HashMap<>(bound);ack.put("version",caption.path("version").asLong());ack.put("phase","caption_rendered");
        assertEquals(200,display.post("ack",ack).statusCode());assertEquals(403,phone.post("ack",ack).statusCode());
        assertEquals(200,display.post("link/unlink",bound).statusCode());assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
        assertEquals(404,phone.post("poll",input).statusCode());
    }
    @Test void phoneCanUnlinkAfterReceiverOutputIsLostWithoutResumingTheReceiver() throws Exception {
        var display=new Client();var phone=new Client();
        var initial=JSON.readTree(display.post("transcription",display.connection(null,0)).body());
        String id=initial.path("assistId").asText();var bound=display.connection(id,initial.path("epoch").asLong());
        String code=JSON.readTree(display.post("link/code",bound).body()).path("code").asText();
        assertEquals(200,phone.post("link/join",Map.of("clientId",phone.client,"code",code,"requestId",UUID.randomUUID().toString())).statusCode());
        assertEquals(200,display.post("link/approve",bound).statusCode());
        var controller=context.getBean(DisplayConversateController.class);
        var bindings=(Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(controller,"bindings");
        var binding=bindings.values().stream().filter(b->id.equals(org.springframework.test.util.ReflectionTestUtils.getField(b,"id"))).findFirst().orElseThrow();
        String owner=(String)org.springframework.test.util.ReflectionTestUtils.getField(binding,"owner");
        var sessions=context.getBean(ConversateSessionService.class);
        var paused=sessions.control(owner,id,initial.path("epoch").asLong(),"transport_pause");
        assertEquals("output_lost",paused.reason());
        var input=phone.connection(id,paused.epoch());
        assertEquals(404,new Client().post("link/unlink",input).statusCode());
        assertEquals(200,phone.post("link/unlink",input).statusCode());
        assertEquals("PAUSED",sessions.status(owner,id).state());
        assertEquals(404,phone.post("poll",input).statusCode());
        var fresh=JSON.readTree(phone.post("transcription",phone.connection(null,0)).body());
        assertTrue(fresh.path("ready").asBoolean());assertFalse(fresh.path("linked").asBoolean());
        assertNotEquals(id,fresh.path("assistId").asText());
    }
    @Test void optionalFinishRetainsFinalAndNextCaptureRejectsOldEpoch() throws Exception {
        var c=new Client();var s=c.bootstrap();var b=c.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        assertEquals(200,c.post("audio/start",b).statusCode());
        var finish=new HashMap<>(b);finish.put("finish",true);
        var response=c.post("audio/stop",finish);assertEquals(200,response.statusCode());var stopped=JSON.readTree(response.body());
        assertTrue(stopped.path("audioFinished").asBoolean());assertFalse(stopped.has("audioRuntime"));assertEquals("STOPPED",stopped.path("audioState").asText());
        assertEquals("마지막 말",stopped.path("caption").path("text").asText());assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
        var restarted=c.post("audio/start",b);assertEquals(200,restarted.statusCode());long next=JSON.readTree(restarted.body()).path("epoch").asLong();assertTrue(next>s.path("epoch").asLong());
        var chunk=new HashMap<>(b);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));assertEquals(409,c.post("audio/chunk",chunk).statusCode());
        assertEquals(200,c.post("audio/stop",c.connection(s.path("assistId").asText(),next)).statusCode());
    }
    @Test void pairCodeExpiresAndCannotPairTheSameBrowserOwner() throws Exception {
        var display=new Client();var phone=new Client();
        var s=JSON.readTree(display.post("transcription",display.connection(null,0)).body());
        var bound=display.connection(s.path("assistId").asText(),s.path("epoch").asLong());
        var code=JSON.readTree(display.post("link/code",bound).body()).path("code").asText();
        var join=new HashMap<String,Object>();join.put("clientId",phone.client);join.put("code",code);join.put("requestId",UUID.randomUUID().toString());
        assertEquals(409,display.post("link/join",join).statusCode());
        var controller=context.getBean(DisplayConversateController.class);
        var bindings=(java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(controller,"bindings");
        for(var binding:bindings.values())if(code.equals(org.springframework.test.util.ReflectionTestUtils.getField(binding,"code")))
            org.springframework.test.util.ReflectionTestUtils.setField(binding,"codeExpires",0L);
        assertEquals(404,phone.post("link/join",join).statusCode());
        assertEquals(409,display.post("link/approve",bound).statusCode());
    }
    @Test void transcriptionBootstrapWorksWithUnavailableLlmAndLinkActionsKeepOriginGuard() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);var manager=mock(com.example.lms.config.LocalLlmProcessManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",manager);
        try{when(manager.diagnostics()).thenReturn(Map.of("enabled",true,"autostart",true,"serviceResponding",false,"modelPresent",false));
            var c=new Client();assertEquals(200,c.post("transcription",c.connection(null,0)).statusCode());
            for(String route:List.of("transcription","link/code","link/join","link/approve","link/unlink","hints","ack"))
                assertEquals(403,c.raw(route,c.connection(null,0),"https://other.example",true).statusCode());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"localLlm",null);}
    }
    @Test void standalonePhoneTestIsDisabledByDefault() throws Exception {
        var c=new Client();assertEquals(404,c.post("phone-test",c.connection(null,0)).statusCode());
    }
    @Test void standalonePhoneTestCapturesOnlyItsOwnAudioWithoutPairingOrLlm() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);var phone=new Client();JsonNode view=null;
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        try {
            var response=phone.post("phone-test",phone.connection(null,0));assertEquals(200,response.statusCode());view=JSON.readTree(response.body());
            assertEquals("STANDALONE",view.path("role").asText());assertFalse(view.path("linked").asBoolean());assertFalse(view.path("hintsEnabled").asBoolean());
            var body=phone.connection(view.path("assistId").asText(),view.path("epoch").asLong());
            assertEquals(403,phone.raw("phone-test",body,"https://foreign.invalid",true).statusCode());
            assertEquals(404,new Client().post("audio/start",body).statusCode());
            var display=new Client();var normal=JSON.readTree(display.post("transcription",display.connection(null,0)).body());
            assertEquals(403,display.post("audio/start",display.connection(normal.path("assistId").asText(),normal.path("epoch").asLong())).statusCode());
            var hints=new HashMap<>(body);hints.put("enabled",true);assertEquals(200,phone.post("hints",hints).statusCode());
            assertTrue(JSON.readTree(phone.post("phone-test",body).body()).path("hintsEnabled").asBoolean(),"reconnect must preserve explicit opt-in");
            hints.put("enabled",false);assertEquals(200,phone.post("hints",hints).statusCode());
            int before=calls.get();assertEquals(200,phone.post("audio/start",body).statusCode());
            var chunk=new HashMap<>(body);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));
            var heard=JSON.readTree(phone.post("audio/chunk",chunk).body());
            assertEquals(SYNTHETIC_FINAL,heard.path("caption").path("text").asText());assertTrue(heard.path("caption").path("isFinal").asBoolean());assertEquals(before,calls.get());
            var finish=new HashMap<>(body);finish.put("finish",true);var stopped=JSON.readTree(phone.post("audio/stop",finish).body());
            assertTrue(stopped.path("audioFinished").asBoolean());assertEquals("마지막 말",stopped.path("caption").path("text").asText());assertEquals(before,calls.get());
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false);
            assertEquals(403,phone.post("audio/start",body).statusCode());
        } finally {
            if(view!=null)phone.post("audio/stop",phone.connection(view.path("assistId").asText(),view.path("epoch").asLong()));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false);
        }
    }
    @Test void standaloneBackgroundIsBoundedAndNeverInvokesGeneration() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);var phone=new Client();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        try {
            var view=JSON.readTree(phone.post("phone-test",phone.connection(null,0)).body());
            var body=phone.connection(view.path("assistId").asText(),view.path("epoch").asLong());body.put("text","합성 배경");
            int before=calls.get();var loaded=phone.post("context",body);assertEquals(200,loaded.statusCode());
            assertEquals(5,JSON.readTree(loaded.body()).path("testStatus").path("backgroundChars").asInt());assertEquals(before,calls.get());
            assertEquals(404,new Client().post("context",body).statusCode());
            assertEquals(403,phone.raw("context",body,"https://foreign.invalid",true).statusCode());
            body.put("text","x".repeat(8001));assertEquals(413,phone.post("context",body).statusCode());
            body.put("text","");assertEquals(200,phone.post("context",body).statusCode());assertEquals(before,calls.get());
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false); }
    }
    @Test void relayDeliversAcrossCookiesFencesOldProducerAndIsolatesAutomation() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        try{
            var a=new Client();var initial=a.connection(null,0);initial.put("activate",true);
            var av=JSON.readTree(a.post("phone-test",initial).body());assertTrue(av.path("ready").asBoolean());
            var ac=a.connection(av.path("assistId").asText(),av.path("epoch").asLong());
            var send=new HashMap<>(ac);send.put("number",1);send.put("fromFold",true);
            assertEquals(200,a.post("relay/test",send).statusCode());
            var reconnect=new HashMap<>(ac);reconnect.put("activate",true);
            var resumed=JSON.readTree(a.post("phone-test",reconnect).body());assertEquals(av.path("epoch").asLong(),resumed.path("epoch").asLong());
            assertEquals("FOLD TEST #001",resumed.path("caption").path("text").asText());
            assertEquals(av.at("/testStatus/relay/generation").asLong(),resumed.at("/testStatus/relay/generation").asLong());
            var lens=new Client();var lc=Map.of("clientId",lens.client,"eventId",0);
            var first=JSON.readTree(lens.post("relay/poll",lc).body());assertEquals("FOLD TEST #001",first.path("caption").path("text").asText());
            for(String field:List.of("assistId","testStatus","clientId","owner","session","processing"))assertFalse(first.has(field),field);
            assertEquals(200,lens.post("relay/ack",Map.of("clientId",lens.client,"eventId",first.path("eventId").asLong())).statusCode());
            var state=JSON.readTree(a.post("poll",ac).body());assertEquals("THIS DEVICE",state.at("/testStatus/relay/eventOwner").asText());
            assertEquals(1,state.at("/testStatus/relay/subscribers").asInt());assertTrue(state.at("/testStatus/relay/lastDisplayAckAt").asLong()>0);
            var automation=new Client("test-1234567890abcdef");var tc=automation.connection(null,0);tc.put("activate",true);
            var tv=JSON.readTree(automation.post("phone-test",tc).body());var test=automation.connection(tv.path("assistId").asText(),tv.path("epoch").asLong());test.put("number",2);test.put("fromFold",false);
            assertEquals(200,automation.post("relay/test",test).statusCode());
            assertEquals("FOLD TEST #001",JSON.readTree(lens.post("relay/poll",lc).body()).path("caption").path("text").asText());
            assertEquals("THIS DEVICE",JSON.readTree(a.post("poll",ac).body()).at("/testStatus/relay/eventOwner").asText());
            var b=new Client();var bc=b.connection(null,0);bc.put("activate",true);var bv=JSON.readTree(b.post("phone-test",bc).body());
            assertEquals(403,a.post("relay/test",send).statusCode());assertEquals(403,a.post("audio/start",ac).statusCode());assertEquals(403,a.post("audio/stop",ac).statusCode());
            assertEquals("OTHER CLIENT",JSON.readTree(a.post("poll",ac).body()).at("/testStatus/relay/eventOwner").asText());
            var newer=b.connection(bv.path("assistId").asText(),bv.path("epoch").asLong());newer.put("number",3);newer.put("fromFold",false);
            assertEquals(200,b.post("relay/test",newer).statusCode());
            var last=JSON.readTree(lens.post("relay/poll",lc).body());assertEquals("DISPLAY TEST #003",last.path("caption").path("text").asText());assertTrue(last.path("eventId").asLong()>first.path("eventId").asLong());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false);}
    }
    @Test void sameCookieNewControlOwnsEventsAndSegmentSettingsStayBounded() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        try{
            var c=new Client("test-fedcba0987654321");var hello=c.connection(null,0);hello.put("activate",true);var first=JSON.readTree(c.post("phone-test",hello).body());
            var old=c.connection(first.path("assistId").asText(),first.path("epoch").asLong());
            String fresh=UUID.randomUUID().toString().replace("-","");hello.put("clientId",fresh);var second=JSON.readTree(c.post("phone-test",hello).body());
            assertEquals(403,c.post("audio/stop",old).statusCode());
            assertEquals("OTHER CLIENT",JSON.readTree(c.post("poll",old).body()).at("/testStatus/relay/eventOwner").asText());
            var settings=c.connection(second.path("assistId").asText(),second.path("epoch").asLong());settings.put("clientId",fresh);settings.put("enabled",true);
            for(int seconds:List.of(5,10,15,23,0)){settings.put("segmentSeconds",seconds);var response=c.post("relay/settings",settings);assertEquals(200,response.statusCode());assertEquals(seconds,JSON.readTree(response.body()).at("/testStatus/relay/segmentSeconds").asInt());}
            settings.put("segmentSeconds",4);assertEquals(400,c.post("relay/settings",settings).statusCode());
            var start=c.connection(second.path("assistId").asText(),second.path("epoch").asLong());start.put("clientId",fresh);start.put("continuation",true);
            assertEquals(409,c.post("audio/start",start).statusCode());
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false);}
    }
    @Test void lensDisplaySettingsRoundTripEchoesAppliedValuesAndRejectsOutOfRange() throws Exception {
        var controller=context.getBean(DisplayConversateController.class);org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",true);
        try{
            var c=new Client("test-aaaa1111bbbb2222");var hello=c.connection(null,0);hello.put("activate",true);
            var view=JSON.readTree(c.post("phone-test",hello).body());
            var conn=c.connection(view.path("assistId").asText(),view.path("epoch").asLong());
            var poll=JSON.readTree(c.post("poll",conn).body());
            assertEquals(20000,poll.at("/testStatus/lensDisplay/hintTtlMs").asLong());
            assertEquals(20000,poll.at("/testStatus/lensDisplay/transcriptTtlMs").asLong());
            assertEquals(11,poll.at("/testStatus/lensDisplay/hintPageLines").asInt());
            assertEquals(5000,poll.at("/testStatus/lensDisplay/autoPageMs").asLong());
            assertEquals(2500,poll.at("/testStatus/lensDisplay/triggerQuietMs").asLong());
            assertEquals(10000,poll.at("/testStatus/lensDisplay/cueCooldownMs").asLong());
            assertEquals(180000,poll.at("/testStatus/lensDisplay/forceAfterMs").asLong());
            var req=new HashMap<>(conn);req.put("display",Map.of("hintTtlMs",40000,"hintPageLines",12,"autoPageMs",3000,"transcriptTtlMs",7000,"triggerQuietMs",7000,"cueCooldownMs",23000,"forceAfterMs",180000));
            var applied=JSON.readTree(c.post("relay/lens-settings",req).body());
            assertEquals(40000,applied.at("/testStatus/lensDisplay/hintTtlMs").asLong());
            assertEquals(7000,applied.at("/testStatus/lensDisplay/transcriptTtlMs").asLong());
            assertEquals(12,applied.at("/testStatus/lensDisplay/hintPageLines").asInt());
            assertEquals(3000,applied.at("/testStatus/lensDisplay/autoPageMs").asLong());
            assertEquals(7000,applied.at("/testStatus/lensDisplay/triggerQuietMs").asLong());
            assertEquals(23000,applied.at("/testStatus/lensDisplay/cueCooldownMs").asLong());
            assertEquals(180000,applied.at("/testStatus/lensDisplay/forceAfterMs").asLong());
            var lens=new Client("test-aaaa1111bbbb2222");
            var event=JSON.readTree(lens.post("relay/poll",Map.of("clientId",lens.client,"eventId",0)).body());
            assertEquals(40000,event.at("/display/hintTtlMs").asLong());assertEquals(12,event.at("/display/hintPageLines").asInt());assertEquals(7000,event.at("/display/transcriptTtlMs").asLong());
            var edge=new HashMap<>(conn);edge.put("display",Map.of("hintTtlMs",1000,"transcriptTtlMs",1000,"autoPageMs",1000));
            var edgeRes=JSON.readTree(c.post("relay/lens-settings",edge).body());
            assertEquals(1000,edgeRes.at("/testStatus/lensDisplay/hintTtlMs").asLong());assertEquals(1000,edgeRes.at("/testStatus/lensDisplay/transcriptTtlMs").asLong());assertEquals(1000,edgeRes.at("/testStatus/lensDisplay/autoPageMs").asLong());
            var edgeMax=new HashMap<>(conn);edgeMax.put("display",Map.of("hintTtlMs",100000,"transcriptTtlMs",100000,"autoPageMs",100000));
            var edgeMaxRes=JSON.readTree(c.post("relay/lens-settings",edgeMax).body());
            assertEquals(100000,edgeMaxRes.at("/testStatus/lensDisplay/hintTtlMs").asLong());assertEquals(100000,edgeMaxRes.at("/testStatus/lensDisplay/transcriptTtlMs").asLong());assertEquals(100000,edgeMaxRes.at("/testStatus/lensDisplay/autoPageMs").asLong());
            var badCap=new HashMap<>(conn);badCap.put("display",Map.of("transcriptTtlMs",100001));
            assertEquals(400,c.post("relay/lens-settings",badCap).statusCode());
            poll=JSON.readTree(c.post("poll",conn).body());assertEquals(100000,poll.at("/testStatus/lensDisplay/hintTtlMs").asLong());
            var prefs=LensDisplayPrefs.defaults(600);
            for(long v:List.of(0L,999L,100001L)){
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,v,null,null,null,null,null,null,null,null,null,null,null));fail("transcriptTtlMs="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,v,null,null,null,null,null,null,null,null,null,null));fail("hintTtlMs="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            }
            for(long v:List.of(999L,100001L))
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,v,null,null,null,null,null,null,null,null,null));fail("autoPageMs="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,99,null,null,null,null,null,null,null,null,null,null,null,null));fail("hintPageLines=99");}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            assertEquals(0,prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,0L,null,null,null,null,null,null,null,null,null)).autoPageMs());
            for(long v:List.of(1000L,7000L,23000L,46000L,100000L)){
                var p=prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,v,v,v,null,null,null,null,null,null,null,null,null));
                assertEquals(v,p.transcriptTtlMs());assertEquals(v,p.hintTtlMs());assertEquals(v,p.autoPageMs());
            }
            for(long v:List.of(1L,4999L,300001L))
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,v,null,null,null,null,null,null));fail("historyWindowMs="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            for(int v:List.of(1,199,8193))
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,null,v,null,null,null,null,null));fail("historyMaxChars="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            for(int v:List.of(1,49,4097))
                try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,null,null,v,null,null,null,null));fail("historyMaxTokens="+v);}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
            var historyPatch=prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,false,30_000L,400,200,false,null,null,null));
            assertEquals(false,historyPatch.historyEnabled());assertEquals(30_000L,historyPatch.historyWindowMs());
            assertEquals(400,historyPatch.historyMaxChars());assertEquals(200,historyPatch.historyMaxTokens());assertEquals(false,historyPatch.topicResetEnabled());
            assertEquals(0,prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,0L,0,0,null,null,null,null)).historyWindowMs());
            var cuePatch=prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,null,null,null,null,7000L,23000L,180000L));
            assertEquals(7000L,cuePatch.triggerQuietMs());assertEquals(23000L,cuePatch.cueCooldownMs());assertEquals(180000L,cuePatch.forceAfterMs());
            try{prefs.patch(new LensDisplayPrefs.Patch(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,601000L));fail("forceAfterMs=601000");}catch(org.springframework.web.server.ResponseStatusException r){assertEquals(400,r.getStatusCode().value());}
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(controller,"phoneTestEnabled",false);}
    }
    @Test void minimalLensLinkOnlySharesItsOwnShortTextWithoutOutputSideEffects() throws Exception {
        var phone=new Client();var s=phone.bootstrap();String id=s.path("assistId").asText();long epoch=s.path("epoch").asLong();
        var connection=phone.connection(id,epoch);
        var grantResponse=phone.post("lens/link",connection);assertEquals(200,grantResponse.statusCode());
        var grant=JSON.readTree(grantResponse.body());String token=grant.path("token").asText();assertTrue(token.matches("[a-f0-9]{64}"));
        assertTrue(grant.path("expiresAt").asLong()>System.currentTimeMillis());
        var lens=new Client();var read=Map.of("token",token);
        assertEquals(404,lens.post("lens/link",connection).statusCode());
        assertEquals(404,lens.post("lens/text",Map.of("token","0".repeat(64))).statusCode());
        assertEquals(403,lens.raw("lens/text",read,"https://other.example",true).statusCode());
        assertEquals(403,lens.raw("lens/text",read,base,false).statusCode());
        var before=JSON.readTree(phone.post("poll",connection).body());
        var emptyResponse=lens.post("lens/text",read);assertEquals(200,emptyResponse.statusCode());
        assertTrue(emptyResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var empty=JSON.readTree(emptyResponse.body());assertEquals(Set.of("conversation","hint","hintId","hintExpiresAt","conversationExpiresAt","display"),JSON.convertValue(empty,Map.class).keySet());
        assertEquals("",empty.path("conversation").asText());assertTrue(empty.path("hintId").isNull());assertEquals(0,empty.path("conversationExpiresAt").asLong());
        var after=JSON.readTree(phone.post("poll",connection).body());assertEquals(before.path("version"),after.path("version"));
        assertEquals(200,phone.post("audio/start",connection).statusCode());
        var chunk=new HashMap<>(connection);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));
        assertEquals(200,phone.post("audio/chunk",chunk).statusCode());
        var text=JSON.readTree(lens.post("lens/text",read).body());assertEquals(SYNTHETIC_FINAL,text.path("conversation").asText());
        assertTrue(text.path("conversation").asText().codePointCount(0,text.path("conversation").asText().length())<=280);
        assertEquals(200,phone.post("audio/stop",connection).statusCode());
        var next=JSON.readTree(phone.post("lens/link",connection).body());assertNotEquals(token,next.path("token").asText());
        assertEquals(404,lens.post("lens/text",read).statusCode());
    }
    static final class Client {
        final String testChannel;
        Client(){this(null);}Client(String testChannel){this.testChannel=testChannel;}
        final String client=UUID.randomUUID().toString().replace("-","");
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        Map<String,Object> connection(String id,long epoch){var b=new HashMap<String,Object>();b.put("assistId",id);b.put("epoch",epoch);b.put("clientId",client);return b;}
        JsonNode bootstrap() throws Exception{var r=post("bootstrap",connection(null,0));assertEquals(200,r.statusCode());assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));assertTrue(r.headers().allValues("Set-Cookie").stream().anyMatch(v->v.contains("HttpOnly")));return JSON.readTree(r.body());}
        HttpResponse<String> post(String path,Object body)throws Exception{return raw(path,body,base,true);}
        HttpResponse<String> raw(String path,Object body,String origin,boolean header)throws Exception{var r=HttpRequest.newBuilder(URI.create(base+"/api/assist/display/"+path)).timeout(Duration.ofSeconds(5)).header("Origin",origin).header("Content-Type","application/json");if(header)r.header("X-Display-Client","1");if(path.equals("relay/diagnostics"))r.header("X-Display-Runtime","a".repeat(32));if(testChannel!=null)r.header("X-Display-Test-Channel",testChannel);return http.send(r.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity
    @Import({DisplayRuntimeDiagnostics.class,DisplayConversateController.class,ConversateController.class,ConversateSessionService.class,InterviewDemoPublicAddress.class,OwnerKeyBootstrapFilter.class,ChatOpenSecurityConfig.class,com.example.lms.api.PublicChatAdmissionGuard.class,com.example.lms.api.PublicRequestBudgetGuard.class,LegacyProbe.class})
    @ImportAutoConfiguration({org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class,JacksonAutoConfiguration.class,SecurityAutoConfiguration.class,SecurityFilterAutoConfiguration.class})
    static class Fixture {
        @Bean ConversateAsrBridge asr(ConversateSessionService sessions,ObjectMapper json){return new ConversateAsrBridge(sessions,json,(events,failure)->{
            if(asrFails.get())throw new java.io.IOException("synthetic_asr_unavailable");
            events.accept(json.createObjectNode().put("type","ready"));
            return new ConversateAsrBridge.Transport(){boolean closed;
                public void send(String line)throws java.io.IOException{var frame=json.readTree(line);events.accept(json.createObjectNode().put("type","ack").put("seq",frame.path("seq").asLong()));
                    events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","synthetic-voice").put("revision",1).put("final",false).put("text","요약"));
                    events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","synthetic-voice").put("revision",2).put("final",true).put("text",SYNTHETIC_FINAL));}
                public java.util.concurrent.CompletableFuture<Void> finish(){events.accept(json.createObjectNode().put("type","transcript").put("utteranceId","synthetic-finish").put("revision",1).put("final",true).put("text","마지막 말"));return java.util.concurrent.CompletableFuture.completedFuture(null);}
                public java.util.concurrent.CompletableFuture<Void> close(){closed=true;return java.util.concurrent.CompletableFuture.completedFuture(null);}public boolean alive(){return !closed;}};});}
        @Bean ClientOwnerKeyResolver owners(jakarta.servlet.http.HttpServletRequest request){return new ClientOwnerKeyResolver(request);}
        @Bean ChatService sharedRag(){var chat=mock(ChatService.class);when(chat.continueChat(any(),any())).thenAnswer(call->{ChatRequestDto request=call.getArgument(0);assertFalse(request.getUseRag());assertNull(request.getSessionId());assertEquals("ephemeral",request.getMemoryMode());calls.incrementAndGet();return ChatResult.of("짧은 공개 답변","local",false);});return chat;}
        @Bean @Order(2) SecurityFilterChain authenticated(HttpSecurity http)throws Exception{return http.authorizeHttpRequests(a->a.anyRequest().authenticated()).exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->r.setStatus(401))).build();}
    }
    @org.springframework.web.bind.annotation.RestController
    static class LegacyProbe {
        static final AtomicInteger calls=new AtomicInteger();
        @org.springframework.web.bind.annotation.RequestMapping({"/api/chat/sync","/api/chat/history","/ws/chat"})
        String legacy(){calls.incrementAndGet();return "synthetic-legacy-response";}
    }
}
