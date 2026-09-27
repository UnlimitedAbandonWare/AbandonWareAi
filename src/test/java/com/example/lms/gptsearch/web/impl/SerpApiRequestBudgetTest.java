package com.example.lms.gptsearch.web.impl;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SerpApiRequestBudgetTest {
    @AfterEach
    void clearContext() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void exhaustedBudgetDoesNotAdmitTransportOrAttempt() {
        SerpApiProvider provider = provider("http://127.0.0.1:1/search.json");
        AtomicInteger calls = new AtomicInteger();
        template(provider).setRequestFactory((uri, method) -> {
            calls.incrementAndGet();
            throw new IOException("unexpected transport");
        });
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime() - 1));

        assertTrue(provider.search(query()).getDocuments().isEmpty());

        assertEquals(0, calls.get());
        assertEquals("request_budget_exhausted", TraceStore.get("web.serpapi.failureReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.cancelled"));
        assertNull(TraceStore.get("web.serpapi.attempt.runs"));
    }

    @Test
    @Timeout(15)
    void concurrentBudgetsBoundSocketWaitWithoutChangingSharedClient() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverWorkers = Executors.newFixedThreadPool(3);
        var callers = Executors.newFixedThreadPool(2);
        AtomicInteger intercepted = new AtomicInteger();
        AtomicInteger headerReceipts = new AtomicInteger();
        server.setExecutor(serverWorkers);
        server.createContext("/search.json", exchange -> {
            try {
                if ("present".equals(exchange.getRequestHeaders().getFirst("X-Test-Policy"))) {
                    headerReceipts.incrementAndGet();
                }
                Thread.sleep(700);
                byte[] body = ("{\"organic_results\":[{\"title\":\"synthetic\","
                        + "\"link\":\"https://example.com/result\",\"snippet\":\"synthetic document\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedAfterClientTimeout) {
                // A timed-out client can close before the delayed response is written.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            SerpApiProvider provider = provider("http://127.0.0.1:" + server.getAddress().getPort() + "/search.json");
            RestTemplate shared = template(provider);
            shared.setInterceptors(List.of((request, body, execution) -> {
                intercepted.incrementAndGet();
                request.getHeaders().set("X-Test-Policy", "present");
                return execution.execute(request, body);
            }));
            var sharedFactory = shared.getRequestFactory();
            var shortRequest = callers.submit(() -> searchWithBudget(provider, 150));
            var longRequest = callers.submit(() -> searchWithBudget(provider, 2_000));
            Outcome shortOutcome = shortRequest.get(8, TimeUnit.SECONDS);
            Outcome longOutcome = longRequest.get(8, TimeUnit.SECONDS);
            var unbudgeted = provider.search(query());

            System.out.printf("SERPAPI_BUDGET shortMs=%d longMs=%d shortDocuments=%d longDocuments=%d%n",
                    shortOutcome.elapsedMs(), longOutcome.elapsedMs(), shortOutcome.documents(), longOutcome.documents());
            assertEquals(0, shortOutcome.documents());
            assertEquals("timeout", shortOutcome.reason());
            assertTrue(shortOutcome.elapsedMs() < 650, "short socket wait must end before the delayed response");
            assertEquals(1, longOutcome.documents());
            assertEquals(1, unbudgeted.getDocuments().size());
            assertEquals(3, intercepted.get());
            assertEquals(3, headerReceipts.get());
            assertSame(sharedFactory, shared.getRequestFactory());
        } finally {
            callers.shutdownNow();
            server.stop(0);
            serverWorkers.shutdownNow();
        }
    }

    private static Outcome searchWithBudget(SerpApiProvider provider, long remainingMs) {
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenReturn(remainingMs);
        TimeBudgetContext.set(budget);
        long start = System.nanoTime();
        try {
            int documents = provider.search(query()).getDocuments().size();
            return new Outcome(documents, (System.nanoTime() - start) / 1_000_000,
                    String.valueOf(TraceStore.get("web.serpapi.failureReason")));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    private record Outcome(int documents, long elapsedMs, String reason) {}

    private static WebSearchQuery query() {
        return new WebSearchQuery("synthetic budget query", 3, null, null);
    }

    private static RestTemplate template(SerpApiProvider provider) {
        return (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
    }

    private static SerpApiProvider provider(String baseUrl) {
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "configEnabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", "fixture-serpapi-budget-key");
        ReflectionTestUtils.setField(provider, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(provider, "timeoutMs", 2_000);
        provider.init();
        return provider;
    }
}
