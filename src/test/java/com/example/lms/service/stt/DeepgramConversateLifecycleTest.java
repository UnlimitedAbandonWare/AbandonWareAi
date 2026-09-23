package com.example.lms.service.stt;

import com.example.lms.config.DeepgramProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.*;
import reactor.netty.http.server.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DeepgramConversateLifecycleTest {
    @Test void actualHandshakePrecedesReadyAndUsesKoreanPrivacyOptions() {
        var connected=new AtomicBoolean();var bytes=new AtomicInteger();var options=new AtomicReference<String>();
        var server=HttpServer.create().host("127.0.0.1").port(0).route(routes->routes.get("/listen",(request,response)->{
            options.set(request.uri());return response.sendWebsocket((inbound,outbound)->inbound.receiveFrames().concatMap(frame->{
                if(frame instanceof BinaryWebSocketFrame binary){assertTrue(connected.get());bytes.addAndGet(binary.content().readableBytes());}
                if(frame instanceof TextWebSocketFrame text&&text.text().contains("CloseStream"))return outbound.sendString(Flux.just(
                        "{\"type\":\"Results\",\"is_final\":true,\"speech_final\":true,\"start\":0,\"duration\":1,\"channel\":{\"alternatives\":[{\"transcript\":\"20년은 아닙니다.\"}]}}", "{\"type\":\"Metadata\",\"duration\":0.02,\"channels\":1}"))
                        .then(outbound.sendClose(1000,""));return Mono.empty();
            }).then());
        })).bindNow();
        try{
            var properties=new DeepgramProperties();properties.setApiKey("synthetic");
            var service=new DeepgramSttService(properties,new ObjectMapper(),new ReactorNettyWebSocketClient(),URI.create("ws://127.0.0.1:"+server.port()+"/listen"));
            var results=service.transcribePcm16Mono(Flux.just(new byte[640]),16000,"ko",()->connected.set(true)).collectList().block(Duration.ofSeconds(10));
            assertTrue(connected.get());assertEquals(640,bytes.get());assertNotNull(results);assertEquals(1,results.size());
            assertEquals("20년은 아닙니다.",results.get(0).transcript());assertEquals(0,results.get(0).start());assertEquals(1,results.get(0).duration());
            for(String option:new String[]{"language=ko","sample_rate=16000","channels=1","model=nova-3","encoding=linear16","interim_results=true","endpointing=600","mip_opt_out=true"})assertTrue(options.get().contains(option),option);
        }finally{server.disposeNow();}
    }
}
