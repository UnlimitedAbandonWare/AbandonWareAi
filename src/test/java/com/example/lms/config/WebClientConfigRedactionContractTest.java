package com.example.lms.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientConfigRedactionContractTest {

    @Test
    void errorBodyDiagnosticsUseHashAndLengthWithoutPreview() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/WebClientConfig.java"));

        assertTrue(source.contains("bodyHash"));
        assertTrue(source.contains("bodyLength"));
        assertTrue(source.contains("ClientResponse.create(resp.statusCode())"));
        assertFalse(source.contains("bodyPreview"));
        assertFalse(source.contains("safeBodyPreview"));
        assertFalse(source.contains("SafeRedactor.safeMessage(body"));
        assertTrue(source.contains("traceSuppressed(\"webClient.correlationTraceStore\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"webclient.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"webclient.suppressed.\" + safeStage + \".errorType\""));
    }

    @Test
    void webClientSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        TraceStore.clear();
        String rawStage = "webClient.correlationTraceStore " + com.example.lms.test.SecretFixtures.openAiKey();
        Method method = WebClientConfig.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        method.setAccessible(true);

        method.invoke(null, rawStage, new IllegalStateException("raw " + com.example.lms.test.SecretFixtures.openAiKey()));

        Object safeStage = TraceStore.get("webclient.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"));
        assertEquals(Boolean.TRUE, TraceStore.get("webclient.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("webclient.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("webclient.suppressed." + safeStage + ".errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(com.example.lms.test.SecretFixtures.openAiKey()));
        TraceStore.clear();
    }

    @Test
    void openAiDefaultBearerHeaderUsesPlaceholderGuard() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/config/WebClientConfig.java"));

        assertFalse(source.contains("openAiApiKey != null && !openAiApiKey.isBlank()"));
        assertTrue(source.contains("ConfigValueGuards.isMissing(openAiApiKey)"));
    }

    @Test
    void errorBodyFilterLogsHeaderPresenceOnlyAndReplaysBody() throws Exception {
        String rawQuery = "private webclient config query";
        String apiKey = "" + com.example.lms.test.SecretFixtures.openAiKey() + "";
        String requestId = "rid-raw-webclient-secret";
        String sessionId = "sid-raw-webclient-secret";
        String body = "{\"error\":\"failed for " + rawQuery + " api_key=" + apiKey + "\"}";
        String url = "https://api.example.test/search?q=private%20webclient%20config%20query&api_key=" + apiKey;

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WebClientConfig.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);

        String replayed;
        try {
            Method method = WebClientConfig.class.getDeclaredMethod("logErrorBodyFilter", int.class);
            method.setAccessible(true);
            ExchangeFilterFunction filter = (ExchangeFilterFunction) method.invoke(null, 2048);
            WebClient client = WebClient.builder()
                    .filter(filter)
                    .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(body)
                            .build()))
                    .build();

            replayed = client.get()
                    .uri(url)
                    .header("X-Request-Id", requestId)
                    .header("X-Session-Id", sessionId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(WebClientResponseException.class,
                            ex -> Mono.just(ex.getResponseBodyAsString()))
                    .block(Duration.ofSeconds(2));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertEquals(body, replayed);
        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertTrue(logged.contains("target=api.example.test/search"), logged);
        assertTrue(logged.contains("queryHash=hash:"), logged);
        assertTrue(logged.contains("bodyHash=hash:"), logged);
        assertTrue(logged.contains("bodyLength="), logged);
        assertTrue(logged.contains("xRequestIdPresent=true"), logged);
        assertTrue(logged.contains("xSessionIdPresent=true"), logged);
        assertFalse(logged.contains(url), logged);
        assertFalse(logged.contains(rawQuery), logged);
        assertFalse(logged.contains(apiKey), logged);
        assertFalse(logged.contains(body), logged);
        assertFalse(logged.contains(requestId), logged);
        assertFalse(logged.contains(sessionId), logged);
    }
}
