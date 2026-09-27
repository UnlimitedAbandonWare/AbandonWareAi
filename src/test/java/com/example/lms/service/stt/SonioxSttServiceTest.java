package com.example.lms.service.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.*;
import reactor.core.publisher.*;
import reactor.netty.http.server.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SonioxSttServiceTest {
    final ObjectMapper json=new ObjectMapper();
    String token(String text,boolean stable,int start,int end){
        return "{\"text\":\""+text+"\",\"is_final\":"+stable+",\"start_ms\":"+start+",\"end_ms\":"+end+"}";
    }
    String tokens(String... values){return "{\"tokens\":["+String.join(",",values)+"]}";}
    WebSocketClient fake(AtomicInteger attempts,Mono<Void> outcome){return new WebSocketClient(){
        public Mono<Void> execute(URI uri,WebSocketHandler handler){attempts.incrementAndGet();return outcome;}
        public Mono<Void> execute(URI uri,HttpHeaders headers,WebSocketHandler handler){return execute(uri,handler);}
    };}
    SonioxSttService service(String key,boolean enabled,String region,WebSocketClient client,URI uri){
        return new SonioxSttService(json,key,enabled,"stt-rt-v5",region,client,uri);
    }
    @Test void missingDisabledPlaceholderAndInvalidRegionMakeZeroAttempts(){
        var attempts=new AtomicInteger();var transport=fake(attempts,Mono.empty());
        for(String key:List.of("","test","dummy","changeme","sk-local","${MISSING}")){
            var s=service(key,true,"us",transport,null);assertFalse(s.isConfigured());
            assertThrows(RuntimeException.class,()->s.transcribePcm16Mono(Flux.empty(),()->{}).collectList().block());
        }
        assertFalse(service("synthetic",false,"us",transport,null).isConfigured());
        assertFalse(service("synthetic",true,"untrusted",transport,null).isConfigured());assertEquals(0,attempts.get());
    }
    @Test void stableTokensArePartialUntilEndpointAndUnstableSuffixIsReplaced(){
        var d=new SonioxSttService.Decoder(json);
        var first=d.accept(tokens(token("안녕",true,0,200),token(" 잘못",false,200,400)));
        assertEquals("안녕 잘못",first.get(0).text());assertFalse(first.get(0).isFinal());
        var revised=d.accept(tokens(token(" 하세요",false,200,400)));assertEquals("안녕 하세요",revised.get(0).text());
        var finalBatch=d.accept(tokens(token(" 하세요",true,200,400),token("<end>",true,400,400)));
        assertEquals(1,finalBatch.size());assertEquals("안녕 하세요",finalBatch.get(0).text());assertTrue(finalBatch.get(0).isFinal());
        assertTrue(d.accept(tokens(token("안녕",true,0,200),token(" 하세요",true,200,400),token("<end>",true,400,400))).isEmpty());
        var next=d.accept(tokens(token("다음",true,500,700),token("<end>",true,700,700)));assertEquals(1,next.get(0).utterance());
    }
    @Test void finishedFlushesOnlyStableTextAndNeverDuplicatesAnEndpoint(){
        var d=new SonioxSttService.Decoder(json);d.accept(tokens(token("확정",true,0,100),token(" 추측",false,100,200)));
        var done=d.accept("{\"finished\":true}");assertEquals("확정",done.get(0).text());assertTrue(done.get(0).isFinal());
        assertTrue(d.accept("{\"finished\":true}").isEmpty());
        var empty=new SonioxSttService.Decoder(json);empty.accept(tokens(token("추측",false,0,100)));assertTrue(empty.accept("{\"finished\":true}").isEmpty());
    }
    @Test void errorBodiesAndTranscriptObjectsAreRedactedAndLimitsFailClosed(){
        for(int code:new int[]{401,402,429,500}){
            var d=new SonioxSttService.Decoder(json);var error=assertThrows(RuntimeException.class,()->d.accept("{\"error_code\":"+code+",\"error_message\":\"private-body\"}"));
            assertFalse(error.toString().contains("private-body"));assertNull(error.getCause());
        }
        assertEquals("soniox:quota_exceeded",assertThrows(RuntimeException.class,()->new SonioxSttService.Decoder(json).accept("{\"error_code\":402}")).getMessage());
        assertThrows(RuntimeException.class,()->new SonioxSttService.Decoder(json).accept(tokens(token("x".repeat(2049),true,0,100))));
        assertThrows(RuntimeException.class,()->new SonioxSttService.Decoder(json).accept("x".repeat(65537)));
        assertFalse(new SonioxSttService.Transcript("private-transcript",true,0).toString().contains("private-transcript"));
    }
    @Test void errorTypeWithoutNumericCodeWinsOverFinishedAndNeverBecomesSuccess(){
        for(var entry:Map.of("unauthorized","auth_failed","insufficient_funds","quota_exceeded","model_not_available","audio_format_invalid").entrySet()){
            var d=new SonioxSttService.Decoder(json);
            var error=assertThrows(RuntimeException.class,()->d.accept("{\"error_type\":\""+entry.getKey()+"\",\"finished\":true,\"error_message\":\"private-body\"}"));
            assertEquals("soniox:"+entry.getValue(),error.getMessage());assertFalse(error.toString().contains("private-body"));
        }
    }
    @Test void cancelDisposesOwnedSocketAndTransportFailureHasNoRawCause(){
        var attempts=new AtomicInteger();var cancelled=new AtomicBoolean();
        var s=service("synthetic",true,"us",fake(attempts,Mono.<Void>never().doOnCancel(()->cancelled.set(true))),null);
        var sub=s.transcribePcm16Mono(Flux.never(),()->{}).subscribe();sub.dispose();assertTrue(cancelled.get());assertEquals(1,attempts.get());
        var failed=service("synthetic",true,"us",fake(attempts,Mono.error(new RuntimeException("private-body"))),null);
        var e=assertThrows(RuntimeException.class,()->failed.transcribePcm16Mono(Flux.empty(),()->{}).collectList().block());
        assertEquals("soniox:transport_error",e.getMessage());assertNull(e.getCause());assertEquals(2,attempts.get());
    }
    @Test void realSocketSendsConfigurationBeforePcmThenDrainsFinished(){
        var frames=new ArrayList<String>();var valid=new AtomicBoolean();var bytes=new AtomicInteger();var connected=new AtomicBoolean();
        String finalMessage=tokens(token("테스트",true,0,500),token("<end>",true,500,500));
        var server=HttpServer.create().host("127.0.0.1").port(0).route(routes->routes.get("/stt",(request,response)->response.sendWebsocket((in,out)->
            in.receiveFrames().concatMap(frame->{
                if(frame instanceof BinaryWebSocketFrame binary){frames.add("pcm");bytes.addAndGet(binary.content().readableBytes());}
                if(frame instanceof TextWebSocketFrame text){
                    if(text.text().isEmpty()){frames.add("end");return out.sendString(Flux.just(finalMessage,"{\"finished\":true}")).then();}
                    frames.add("config");try{var c=json.readTree(text.text());valid.set(c.path("api_key").asText().equals("synthetic")&&c.path("model").asText().equals("stt-rt-v5")&&c.path("audio_format").asText().equals("pcm_s16le")&&c.path("sample_rate").asInt()==16000&&c.path("enable_endpoint_detection").asBoolean());}catch(Exception e){throw new AssertionError();}
                }return Mono.empty();
            }).then()))).bindNow();
        try{
            var s=service("synthetic",true,"us",new ReactorNettyWebSocketClient(),URI.create("ws://127.0.0.1:"+server.port()+"/stt"));
            var result=s.transcribePcm16Mono(Flux.just(new byte[640]),()->connected.set(true)).collectList().block(Duration.ofSeconds(8));
            assertTrue(valid.get());assertTrue(connected.get());assertEquals(List.of("config","pcm","end"),frames);assertEquals(640,bytes.get());
            assertNotNull(result);assertEquals(1,result.size());assertTrue(result.get(0).isFinal());assertEquals("테스트",result.get(0).text());
        }finally{server.disposeNow();}
    }
}
