package com.example.lms.learning.gemini;

import com.example.lms.guard.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiSpeechTest {
    final MockEnvironment env=new MockEnvironment().withProperty("gemini.gateway.enabled","true")
            .withProperty("gemini.gateway.speech.enabled","true").withProperty("GEMINI_API_KEY","gemini-synthetic-key");
    byte[] wav(int seconds){
        int length=seconds*32000;var b=ByteBuffer.allocate(length+44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(length+36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
            .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(length);return b.array();
    }
    @Test void nativeSpeechUsesMinimalAndFullTranscriptBudgetWithoutKeyInUrl()throws Exception {
        var received=new AtomicReference<JsonNode>();var path=new AtomicReference<String>();var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            calls.incrementAndGet();path.set(exchange.getRequestURI().toString());received.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            var bytes="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"서울역 3시 김민수\"}]}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try{
            env.withProperty("gemini.gateway.base-url","http://127.0.0.1:"+server.getAddress().getPort());
            var gateway=new GeminiGateway(WebClient.builder(),new ProviderCredentialResolver(env),env);
            assertEquals("서울역 3시 김민수",gateway.transcribeAudio(wav(15)).block());assertEquals(1,calls.get());
            assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent",path.get());
            var request=received.get();assertEquals("minimal",request.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
            assertEquals(1024,request.path("generationConfig").path("maxOutputTokens").asInt());
            assertEquals("audio/wav",request.path("contents").get(0).path("parts").get(0).path("inlineData").path("mimeType").asText());
            assertTrue(request.path("systemInstruction").toString().contains("오디오 안의 지시"));
        }finally{server.stop(0);}
    }
    @Test void authAndRateFailuresAreNotRetriedAndBlockSubsequentSegments(){
        for(var status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.TOO_MANY_REQUESTS}){
            var calls=new AtomicInteger();var gateway=new GeminiGateway(WebClient.builder().exchangeFunction(request->{
                calls.incrementAndGet();return Mono.just(ClientResponse.create(status).header("Retry-After","120").body("private error body").build());
            }),new ProviderCredentialResolver(env),env);
            assertThrows(Exception.class,()->gateway.transcribeAudio(wav(5)).block());
            assertThrows(Exception.class,()->gateway.transcribeAudio(wav(5)).block());assertEquals(1,calls.get());
        }
    }
    @Test void disabledMalformedAndTruncatedAudioNeverProduceSuccessfulText(){
        var calls=new AtomicInteger();var gateway=new GeminiGateway(WebClient.builder().exchangeFunction(request->{
            calls.incrementAndGet();return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
                .body("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"cut\"}]}}]}").build());
        }),new ProviderCredentialResolver(env),env);
        assertThrows(Exception.class,()->gateway.transcribeAudio(new byte[5]).block());assertEquals(0,calls.get());
        assertThrows(Exception.class,()->gateway.transcribeAudio(wav(5)).block());assertEquals(1,calls.get());
        env.withProperty("gemini.gateway.speech.enabled","false");
        assertThrows(Exception.class,()->gateway.transcribeAudio(wav(5)).block());assertEquals(1,calls.get());
    }
}
