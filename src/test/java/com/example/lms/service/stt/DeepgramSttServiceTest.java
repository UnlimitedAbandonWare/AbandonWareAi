package com.example.lms.service.stt;

import com.example.lms.config.DeepgramProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeepgramSttServiceTest {
    @Test void retainsWordTimesConfidenceAndSpeakerWithoutLeakingTranscriptInDiagnostics() throws Exception {
        var props=new DeepgramProperties();
        var service=new DeepgramSttService(props,new ObjectMapper());
        var parse=DeepgramSttService.class.getDeclaredMethod("parse",String.class);parse.setAccessible(true);
        var result=parse.invoke(service,"{\"type\":\"Results\",\"is_final\":true,\"speech_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"synthetic caption\",\"confidence\":0.87,\"words\":[{\"word\":\"synthetic\",\"punctuated_word\":\"Synthetic\",\"start\":0.2,\"end\":0.7,\"confidence\":0.91,\"speaker\":0}]}]}}");
        var value=new ObjectMapper().valueToTree(result);
        assertEquals(0.87,value.path("confidence").asDouble());
        assertEquals(0.2,value.path("words").path(0).path("start").asDouble());
        assertEquals(0,value.path("words").path(0).path("speaker").asInt(-1));
        assertFalse(result.toString().contains("synthetic caption"));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeepgramProperties.class)
    @Import(DeepgramSttService.class)
    static class Wiring {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }

    @Test
    void serviceBeanIsInjectableWithoutOpeningAConnectionAtStartup() {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("deepgram.api-key=synthetic-secret").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(DeepgramSttService.class));
                    assertTrue(context.getBean(DeepgramProperties.class).isConfigured());
                });
    }

    private Object service(String key, WebSocketClient transport, URI uri) {
        var properties = new DeepgramProperties();
        properties.setApiKey(key);
        return assertDoesNotThrow(() -> {
            var type = Class.forName("com.example.lms.service.stt.DeepgramSttService");
            var constructor = type.getDeclaredConstructor(DeepgramProperties.class, ObjectMapper.class,
                    WebSocketClient.class, URI.class);
            constructor.setAccessible(true);
            return constructor.newInstance(properties, new ObjectMapper(), transport, uri);
        }, "an injectable Deepgram service must exist");
    }

    private Flux<?> stream(Object service, Flux<byte[]> chunks) {
        return assertDoesNotThrow(() -> (Flux<?>) service.getClass()
                .getMethod("transcribePcm16Mono", Flux.class, int.class, String.class)
                .invoke(service, chunks, 16000, "en"));
    }

    private WebSocketClient fake(AtomicInteger attempts, Mono<Void> outcome) {
        return new WebSocketClient() {
            public Mono<Void> execute(URI uri, WebSocketHandler handler) {
                return execute(uri, new HttpHeaders(), handler);
            }
            public Mono<Void> execute(URI uri, HttpHeaders headers, WebSocketHandler handler) {
                attempts.incrementAndGet();
                return outcome;
            }
        };
    }

    @Test
    void missingCredentialMakesZeroConnectionAttempts() {
        var attempts = new AtomicInteger();
        Object service = service("", fake(attempts, Mono.empty()), URI.create("ws://127.0.0.1/listen"));
        var error = assertThrows(RuntimeException.class,
                () -> stream(service, Flux.just(new byte[320])).collectList().block(Duration.ofSeconds(3)));
        assertEquals("deepgram:missing_api_key", error.getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void transportErrorsAreRedactedAndNeverRetried() {
        var attempts = new AtomicInteger();
        Object service = service("synthetic-secret", fake(attempts,
                Mono.error(new IllegalArgumentException("synthetic-secret private-provider-body"))),
                URI.create("ws://127.0.0.1/listen"));
        var error = assertThrows(RuntimeException.class,
                () -> stream(service, Flux.just(new byte[320])).collectList().block(Duration.ofSeconds(3)));
        assertEquals("deepgram:transport_error", error.getMessage());
        assertNull(error.getCause());
        assertEquals(1, attempts.get());
    }

    @Test
    void consumerCancellationCancelsTheOwnedTransport() {
        var cancelled = new AtomicBoolean();
        Object service = service("synthetic-secret", fake(new AtomicInteger(),
                Mono.<Void>never().doOnCancel(() -> cancelled.set(true))), URI.create("ws://127.0.0.1/listen"));
        var subscription = stream(service, Flux.never()).subscribe();
        subscription.dispose();
        assertTrue(cancelled.get());
    }

    @Test
    void realWebSocketSendsPcmChunksAndCloseStreamThenReceivesFinalResults() {
        var receivedBytes = new AtomicInteger();
        var headerValid = new AtomicBoolean();
        var closeStreamReceived = new AtomicBoolean();
        var server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes.get("/listen", (request, response) -> {
            headerValid.set("Token synthetic-secret".equals(request.requestHeaders().get("Authorization")));
            return response.sendWebsocket((inbound, outbound) -> inbound.receiveFrames().concatMap(frame -> {
                if (frame instanceof BinaryWebSocketFrame binary) receivedBytes.addAndGet(binary.content().readableBytes());
                if (frame instanceof TextWebSocketFrame text && text.text().equals("{\"type\":\"CloseStream\"}")) {
                    closeStreamReceived.set(true);
                    return outbound.sendString(Flux.just("{\"type\":\"Results\",\"is_final\":true,\"speech_final\":true,"
                            + "\"channel\":{\"alternatives\":[{\"transcript\":\"Hello.\"}]}}", "{\"type\":\"Metadata\",\"duration\":0.03,\"channels\":1}"))
                            .then(outbound.sendClose(1000, ""));
                }
                return Mono.empty();
            }).then());
        })).bindNow();
        try {
            Object service = service("synthetic-secret", new ReactorNettyWebSocketClient(),
                    URI.create("ws://127.0.0.1:" + server.port() + "/listen"));
            var results = stream(service, Flux.just(new byte[320], new byte[640]))
                    .collectList().block(Duration.ofSeconds(10));
            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals("Hello.", assertDoesNotThrow(() -> results.get(0).getClass().getMethod("transcript").invoke(results.get(0))));
            assertFalse(results.get(0).toString().contains("Hello."));
            assertEquals(960, receivedBytes.get());
            assertTrue(headerValid.get());
            assertTrue(closeStreamReceived.get());
        } finally { server.disposeNow(); }
    }

    @Test void close1000WithoutTerminalMetadataDoesNotConfirmFinish() {
        var closeReceived=new AtomicBoolean();
        var server=HttpServer.create().host("127.0.0.1").port(0).route(routes->routes.get("/listen",(request,response)->
                response.sendWebsocket((in,out)->in.receiveFrames().concatMap(frame->{
                    if(frame instanceof TextWebSocketFrame text&&text.text().contains("CloseStream")){
                        closeReceived.set(true);return out.sendClose(1000,"");
                    }
                    return Mono.empty();
                }).then()))).bindNow();
        try {
            var service=service("synthetic-secret",new ReactorNettyWebSocketClient(),URI.create("ws://127.0.0.1:"+server.port()+"/listen"));
            var error=assertThrows(RuntimeException.class,()->stream(service,Flux.just(new byte[640])).collectList().block(Duration.ofSeconds(5)));
            assertTrue(closeReceived.get());assertEquals("deepgram:finish_unconfirmed",error.getMessage());
        }finally{server.disposeNow();}
    }
}
