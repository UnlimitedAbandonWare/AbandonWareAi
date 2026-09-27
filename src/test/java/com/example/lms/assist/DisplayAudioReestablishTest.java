package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Mid-stream drop re-establishment: a resumable audio segment (finished /
    WAITING / API_PAUSED) accepts a continuation whose epoch drifted
    server-side, keeps the same assistId and rolling context, and never
    weakens the duplicate-start / planned-renewal gates. */
class DisplayAudioReestablishTest {
    static ServletWebServerApplicationContext context;
    static final ObjectMapper JSON=DisplayConversateHttpTest.JSON;
    @BeforeAll static void boot(){
        var app=new SpringApplication(DisplayConversateHttpTest.Fixture.class);app.setWebApplicationType(WebApplicationType.SERVLET);
        context=(ServletWebServerApplicationContext)app.run("--spring.config.location=optional:classpath:/assist-fixture-empty.properties","--server.address=127.0.0.1","--server.port=0","--conversate.enabled=true","--conversate.display.enabled=true","--conversate.display.audio.enabled=true","--conversate.display.sticky-lens=false","--demo.interview.enabled=false","--spring.jackson.deserialization.fail-on-unknown-properties=true","--spring.main.banner-mode=off","--logging.level.root=ERROR");
        DisplayConversateHttpTest.base="http://127.0.0.1:"+context.getWebServer().getPort();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    private String owner(String id){
        var bindings=(Map<?,?>)ReflectionTestUtils.getField(context.getBean(DisplayConversateController.class),"bindings");
        var binding=bindings.values().stream().filter(b->id.equals(ReflectionTestUtils.getField(b,"id"))).findFirst().orElseThrow();
        return (String)ReflectionTestUtils.getField(binding,"owner");
    }
    private int contextTurns(String id){
        return context.getBean(ConversateSessionService.class).status(owner(id),id).metrics().contextTurns();
    }
    @Test void staleEpochContinuationAfterFinishKeepsAssistAndRollingContext() throws Exception {
        var phone=new DisplayConversateHttpTest.Client();var s=phone.bootstrap();String id=s.path("assistId").asText();long epoch=s.path("epoch").asLong();
        var bound=phone.connection(id,epoch);
        assertEquals(200,phone.post("audio/start",bound).statusCode());
        var chunk=new HashMap<>(bound);chunk.put("sequence",0);chunk.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));
        assertEquals(200,phone.post("audio/chunk",chunk).statusCode());
        var finish=new HashMap<>(bound);finish.put("finish",true);
        assertEquals(200,phone.post("audio/stop",finish).statusCode());
        assertTrue(contextTurns(id)>0);
        var drifted=context.getBean(ConversateSessionService.class).control(owner(id),id,epoch,"text_fallback");assertTrue(drifted.epoch()>epoch);
        Thread.sleep(600);
        var stale=new HashMap<String,Object>(bound);stale.put("continuation",true);
        var resumed=phone.post("audio/start",stale);assertEquals(200,resumed.statusCode());
        var view=JSON.readTree(resumed.body());assertEquals(id,view.path("assistId").asText());assertTrue(view.path("epoch").asLong()>drifted.epoch());
        assertTrue(contextTurns(id)>0);
        var renewed=phone.connection(id,view.path("epoch").asLong());renewed.put("sequence",0);renewed.put("pcm",Base64.getEncoder().encodeToString(new byte[640]));
        assertEquals(200,phone.post("audio/chunk",renewed).statusCode());
        var again=phone.connection(id,view.path("epoch").asLong());again.put("continuation",true);
        assertEquals(409,phone.post("audio/start",again).statusCode());
        assertEquals(200,phone.post("audio/stop",phone.connection(id,view.path("epoch").asLong())).statusCode());
        assertEquals(0,context.getBean(ConversateAsrBridge.class).activeCount());
    }
    @Test void staleEpochWithoutResumableAudioStillConflicts() throws Exception {
        var phone=new DisplayConversateHttpTest.Client();var s=phone.bootstrap();String id=s.path("assistId").asText();long epoch=s.path("epoch").asLong();
        var bound=phone.connection(id,epoch);
        var first=phone.post("audio/start",bound);assertEquals(200,first.statusCode(),first.body());
        context.getBean(ConversateSessionService.class).control(owner(id),id,epoch,"text_fallback");
        var stale=new HashMap<String,Object>(bound);stale.put("continuation",true);
        assertEquals(409,phone.post("audio/start",stale).statusCode());
        assertEquals(409,phone.post("audio/start",bound).statusCode());
    }
}
