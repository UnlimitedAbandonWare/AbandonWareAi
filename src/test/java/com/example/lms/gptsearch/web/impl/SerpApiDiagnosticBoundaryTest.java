package com.example.lms.gptsearch.web.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.search.TraceStore;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SerpApiDiagnosticBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "http-error", "resource-error"})
    void queryAuthenticationDoesNotEscapeThroughProviderTraceOrLogs(String scenario) {
        // Synthetic short sentinel: no credential or actual HTTP request is used.
        String key = "fixture-auth-" + "cedar-48";
        String query = "orchid probe phrase";
        TraceStore.clear();
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(SerpApiProvider.class),
                (Logger) LoggerFactory.getLogger(AbstractWebSearchProvider.class));
        List<Level> levels = loggers.stream().map(Logger::getLevel).toList();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        loggers.forEach(logger -> { logger.setLevel(Level.DEBUG); logger.addAppender(events); });
        try {
            SerpApiProvider provider = new SerpApiProvider();
            ReflectionTestUtils.setField(provider, "configEnabled", true);
            ReflectionTestUtils.setField(provider, "apiKey", key);
            ReflectionTestUtils.setField(provider, "baseUrl", "https://serpapi.example.test/search.json");
            ReflectionTestUtils.setField(provider, "timeoutMs", 2000);
            ReflectionTestUtils.invokeMethod(provider, "init");
            assertTrue(provider.isEnabled());
            RestTemplate rest = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
            AtomicInteger attempts = new AtomicInteger();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            server.expect(request -> {
                attempts.incrementAndGet();
                var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
                assertTrue(key.equals(params.getFirst("api_key")), "synthetic query credential present");
                assertNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            }).andRespond(request -> {
                if (scenario.equals("resource-error"))
                    throw new ResourceAccessException("fixture transport " + request.getURI(), new IOException(key));
                if (scenario.equals("http-error"))
                    return withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                            .body("fixture server echo " + key + " " + request.getURI()).createResponse(request);
                return withSuccess("{\"organic_results\":[{\"title\":\"local result\",\"link\":\"https://reference.example.test/a\",\"snippet\":\"local fixture\"}]}",
                        MediaType.APPLICATION_JSON).createResponse(request);
            });
            var result = provider.search(new WebSearchQuery(query, 1, null, null));
            server.verify();
            assertEquals(1, attempts.get());
            assertEquals(scenario.equals("success") ? 1 : 0, result.getDocuments().size());
            String trace = TraceStore.getAll().toString();
            String logs = events.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(events.list.isEmpty(), "logger capture must have observed provider events");
            for (String captured : List.of(trace, logs)) {
                assertFalse(captured.contains(key), "raw synthetic auth sentinel absent");
                assertFalse(captured.contains(query), "raw synthetic query absent");
                assertFalse(captured.contains("api_key="), "full query-auth URI absent");
            }
            assertTrue(events.list.stream().allMatch(event -> event.getThrowableProxy() == null),
                    "provider logs must not attach a throwable carrying the URI");
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).detachAppender(events);
                loggers.get(i).setLevel(levels.get(i));
            }
            events.stop();
            TraceStore.clear();
        }
    }
}
