package com.example.lms.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientDiagnosticsTest {

    @Test
    void diagnosticsLogHashesAndHostPathWithoutRawUrlQueryOrBody() {
        String rawQuery = "private search query";
        String apiKey = "sk-secret123456";
        String body = "{\"error\":\"failed for " + rawQuery + " api_key=" + apiKey + "\"}";
        String url = "https://api.example.test/search?q=private%20search%20query&api_key=" + apiKey;

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WebClientDiagnostics.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            WebClient.Builder builder = WebClient.builder()
                    .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(body)
                            .build()));
            new WebClientDiagnostics().debugWebClientCustomizer().customize(builder);

            builder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(WebClientResponseException.class,
                            ex -> Mono.just(ex.getResponseBodyAsString()))
                    .block(Duration.ofSeconds(2));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        String logged = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertTrue(logged.contains("target=api.example.test/search"), logged);
        assertTrue(logged.contains("hasQuery=true"), logged);
        assertTrue(logged.contains("queryHash=hash:"), logged);
        assertTrue(logged.contains("bodyHash=hash:"), logged);
        assertTrue(logged.contains("bodyLength="), logged);
        assertFalse(logged.contains(url), logged);
        assertFalse(logged.contains(rawQuery), logged);
        assertFalse(logged.contains(apiKey), logged);
        assertFalse(logged.contains(body), logged);
    }
}
