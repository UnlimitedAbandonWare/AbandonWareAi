package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.TavilyWebSearchRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavilyProviderTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void disabledRetrieverProducesProviderDisabledTraceWithoutExternalCall() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        String rawQuery = "private tavily bridge query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                3,
                List.of(ProviderId.TAVILY),
                Duration.ofDays(1)
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals("tavily.enabled=false", TraceStore.get("web.tavily.disabledReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.skipped"));
        assertEquals("tavily.enabled=false", TraceStore.get("web.tavily.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertEquals(3, TraceStore.get("web.tavily.requestedCount"));
        assertTrue(String.valueOf(TraceStore.get("web.tavily.queryHash")).startsWith("hash:"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void disabledRetrieverClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        String rawQuery = "private tavily disabled residue query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                3,
                List.of(ProviderId.TAVILY),
                Duration.ofDays(1)
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void delegatesToExistingRetrieverAndMapsContentMetadataToWebDocuments() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new ReturningRetriever());

        var result = provider.search(new WebSearchQuery(
                "current spring boot docs?",
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals("Tavily Result", result.getDocuments().get(0).getTitle());
        assertEquals("https://example.com/tavily", result.getDocuments().get(0).getUrl());
        assertEquals("retrieved tavily snippet", result.getDocuments().get(0).getSnippet());
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(1, TraceStore.get("web.tavily.bridgeDocumentCount"));
    }

    @Test
    void successfulDelegateClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.providerEmpty", true);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.failureReason", "timeout");
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new ReturningRetriever());

        var result = provider.search(new WebSearchQuery(
                "current spring boot docs?",
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.zeroResults"));
        assertEquals("", TraceStore.get("web.tavily.failureReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
    }

    @Test
    void emptyDelegateWithoutFailureReasonGetsProviderEmptyBridgeFallback() {
        TraceStore.clear();
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new EmptyRetriever());
        String rawQuery = "private tavily bridge empty query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(0, TraceStore.get("web.tavily.bridgeDocumentCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals("provider-empty", TraceStore.get("web.tavily.failureReason"));
        assertEquals(2, TraceStore.get("web.tavily.requestedCount"));
        assertEquals(0, TraceStore.get("web.tavily.returnedCount"));
        assertEquals(0, TraceStore.get("web.tavily.afterFilterCount"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void emptyDelegateClearsStaleProviderFailureResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.failureReason", "timeout");
        TraceStore.put("web.tavily.timeout", true);
        TraceStore.put("web.tavily.cancelled", true);
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new EmptyRetriever());
        String rawQuery = "private tavily empty residue query";

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(null, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals("provider-empty", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void zeroAndNegativeTopKReturnEmptyWithoutRetrieverCall() {
        TraceStore.clear();
        AtomicInteger attempts = new AtomicInteger();
        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", new TavilyWebSearchRetriever(WebClient.builder()) {
            @Override
            public List<Content> retrieve(Query query) {
                attempts.incrementAndGet();
                return List.of();
            }
        });
        String zeroQuery = "private tavily zero top k query";
        String negativeQuery = "private tavily negative top k query";

        assertTrue(provider.search(new WebSearchQuery(
                zeroQuery,
                0,
                List.of(ProviderId.TAVILY),
                null
        )).getDocuments().isEmpty());
        assertTrue(provider.search(new WebSearchQuery(
                negativeQuery,
                -1,
                List.of(ProviderId.TAVILY),
                null
        )).getDocuments().isEmpty());

        assertEquals(0, attempts.get());
        assertEquals(0, TraceStore.get("web.tavily.requestedCount"));
        assertEquals("non_positive_top_k", TraceStore.get("web.tavily.skipped.reason"));
        assertEquals("non_positive_top_k", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(zeroQuery));
        assertFalse(TraceStore.getAll().toString().contains(negativeQuery));
    }

    @Test
    void nonPositiveTopKClearsPriorRemoteFailureTraceProjection() {
        TraceStore.clear();
        TraceStore.put("web.tavily.exceptionType", "WebClientResponseException");
        TraceStore.put("web.tavily.errorType", "IllegalStateException");
        TraceStore.put("web.tavily.tookMs", 999L);
        TraceStore.put("web.tavily.errorBodyHash", "hash:stale");
        TraceStore.put("web.tavily.errorBodyLength", 42);
        TraceStore.put("web.tavily.queryTokenBucket", "stale-bucket");
        TraceStore.put("web.tavily.timeoutMs", 999);
        TraceStore.put("web.tavily.endpointHost", "stale.example");
        TraceStore.put("web.tavily.cooldown.reason", "rate-limit");
        TraceStore.put("web.tavily.cooldown.hintMs", 30_000L);
        TraceStore.put("web.tavily.retryAfterMs", 30_000L);
        TraceStore.put("web.provider.name", "brave");
        TraceStore.put("web.provider.enabled", false);
        TraceStore.put("web.provider.resultCount", 7);
        TraceStore.put("web.provider.disabledReason", "missing-key");
        TraceStore.put("web.query.hash", "hash:stale");
        TraceStore.put("web.query.length", 123);
        TraceStore.put("web.failsoft.reason", "timeout");
        TraceStore.put("web.filter.starvationReason", "after-filter-starvation");
        TavilyProvider provider = new TavilyProvider();
        String rawQuery = "private tavily no-op transition query";

        assertTrue(provider.search(new WebSearchQuery(
                rawQuery,
                0,
                List.of(ProviderId.TAVILY),
                null
        )).getDocuments().isEmpty());

        assertEquals(null, TraceStore.get("web.tavily.exceptionType"));
        assertEquals(null, TraceStore.get("web.tavily.errorType"));
        assertEquals(null, TraceStore.get("web.tavily.tookMs"));
        assertEquals(null, TraceStore.get("web.tavily.errorBodyHash"));
        assertEquals(null, TraceStore.get("web.tavily.errorBodyLength"));
        assertEquals(null, TraceStore.get("web.tavily.queryTokenBucket"));
        assertEquals(null, TraceStore.get("web.tavily.timeoutMs"));
        assertEquals(null, TraceStore.get("web.tavily.endpointHost"));
        assertEquals(null, TraceStore.get("web.tavily.cooldown.reason"));
        assertEquals(null, TraceStore.get("web.tavily.cooldown.hintMs"));
        assertEquals(null, TraceStore.get("web.tavily.retryAfterMs"));
        assertEquals("tavily", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.provider.enabled"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertEquals(null, TraceStore.get("web.provider.disabledReason"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("web.query.length"));
        assertEquals("non_positive_top_k", TraceStore.get("web.failsoft.reason"));
        assertEquals(null, TraceStore.get("web.filter.starvationReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    @Test
    void existingRetrieverPreservesTavilyTitleAndUrlMetadataForProviderBridge() {
        TraceStore.clear();
        String rawQuery = "private tavily metadata bridge query";
        String apiKey = "tvly-test-metadata-key";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"results":[{"title":"Tavily Credits","url":"https://docs.tavily.com/documentation/api-credits","content":"monthly credits snippet"}]}
                                """)
                        .build())));
        ReflectionTestUtils.setField(retriever, "apiKey", apiKey);
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 2);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        TavilyProvider provider = new TavilyProvider();
        ReflectionTestUtils.setField(provider, "retriever", retriever);

        var result = provider.search(new WebSearchQuery(
                rawQuery,
                2,
                List.of(ProviderId.TAVILY),
                null
        ));

        assertEquals(1, result.getDocuments().size());
        assertEquals("Tavily Credits", result.getDocuments().get(0).getTitle());
        assertEquals("https://docs.tavily.com/documentation/api-credits", result.getDocuments().get(0).getUrl());
        assertTrue(result.getDocuments().get(0).getSnippet().contains("monthly credits snippet"));
        assertEquals(1, TraceStore.get("web.tavily.returnedCount"));
        assertEquals(1, TraceStore.get("web.tavily.afterFilterCount"));
        assertEquals("TavilyWebSearchRetriever", TraceStore.get("web.tavily.providerBridge"));
        assertEquals(1, TraceStore.get("web.tavily.bridgeDocumentCount"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
        assertFalse(TraceStore.getAll().toString().contains(apiKey));
    }


    @Test
    @org.junit.jupiter.api.Timeout(15)
    void existingRetrieverLoopbackCarriesRequestScopedAttempt() throws Exception {
        TraceStore.clear();
        String rawQuery = "sec08 synthetic tavily document";
        String syntheticKey = "fixture-tavily-lineage-key";
        var receipts = new AtomicInteger();
        var clientExchanges = new AtomicInteger();
        var queryHash = new java.util.concurrent.atomic.AtomicReference<String>();
        var requestShapeMatches = new java.util.concurrent.atomic.AtomicBoolean();
        var boundaryIds = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            try {
                receipts.incrementAndGet();
                var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
                queryHash.set(com.example.lms.trace.SafeRedactor.hashValue(body.path("query").asText()));
                requestShapeMatches.set("POST".equals(exchange.getRequestMethod())
                        && syntheticKey.equals(body.path("api_key").asText()) && body.path("max_results").asInt() == 2);
                byte[] response = "{\"results\":[{\"title\":\"Synthetic reference\",\"url\":\"https://example.invalid/reference\",\"content\":\"synthetic result\"}]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                    .filter((request, next) -> {
                        clientExchanges.incrementAndGet();
                        boundaryIds.set(TraceStore.searchCorrelation(TraceStore.context()));
                        return next.exchange(request);
                    }));
            ReflectionTestUtils.setField(retriever, "apiKey", syntheticKey);
            ReflectionTestUtils.setField(retriever, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            ReflectionTestUtils.setField(retriever, "maxResults", 2);
            ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);
            TavilyProvider provider = new TavilyProvider();
            ReflectionTestUtils.setField(provider, "retriever", retriever);
            TraceStore.withSearchContext("retrievalExecutionId", () ->
                    TraceStore.withSearchContext("searchExecutionId", () -> {
                        Map<String, Object> logicalIds = TraceStore.searchCorrelation(TraceStore.context());
                        assertEquals(2, logicalIds.size());
                        var result = provider.search(new WebSearchQuery(rawQuery, 2, List.of(ProviderId.TAVILY), null));
                        assertEquals(1, result.getDocuments().size());
                        assertEquals(1, clientExchanges.get());
                        assertEquals(1, receipts.get());
                        assertTrue(requestShapeMatches.get());
                        assertEquals(queryHash.get(), TraceStore.get("web.tavily.queryHash"));
                        assertEquals(1, TraceStore.get("web.tavily.returnedCount"));
                        assertEquals(1, TraceStore.get("web.tavily.afterFilterCount"));
                        assertEquals(logicalIds, TraceStore.searchCorrelation(TraceStore.context()));
                        assertEquals(3, boundaryIds.get().size());
                        Map<?, ?> row = onlyTavilyAttempt();
                        boundaryIds.get().forEach((key, value) -> assertEquals(value, row.get(key)));
                        assertEquals(queryHash.get(), row.get("queryHash"));
                        assertEquals(200, row.get("httpStatus"));
                        assertEquals(1, row.get("returnedCount"));
                        assertEquals(1, row.get("afterFilterCount"));
                        assertEquals("OK", row.get("outcome"));
                        assertEquals("none", row.get("failureReason"));
                        assertEquals("tavily", row.get("provider"));
                        assertEquals("raw_results_and_content_list", row.get("countScope"));
                        assertEquals(true, row.get("clientAttemptObserved"));
                        assertEquals("webclient_exchange", row.get("clientAttemptBoundary"));
                        assertEquals(false, row.get("providerReceiptObserved"));
                        assertTrue(((Number) row.get("elapsedMs")).longValue() >= 0);
                        assertTrue(((Number) row.get("finishedAtEpochMs")).longValue() >= ((Number) row.get("startedAtEpochMs")).longValue());
                        assertEquals(java.util.Set.of("retrievalExecutionId", "searchExecutionId", "providerAttemptId", "provider", "queryHash", "countScope",
                                "clientAttemptObserved", "clientAttemptBoundary", "providerReceiptObserved", "startedAtEpochMs", "finishedAtEpochMs",
                                "elapsedMs", "httpStatus", "returnedCount", "afterFilterCount", "outcome", "failureReason"), row.keySet());
                        String trace = TraceStore.getAll().toString();
                        assertFalse(trace.contains(rawQuery));
                        assertFalse(trace.contains(syntheticKey));
                        assertFalse(trace.contains("synthetic result"));
                        System.out.println("SEC08_TAVILY logicalScopes=2 clientExchanges=1 loopbackReceipts=1 boundaryScopes="
                                + boundaryIds.get().size() + " returned=1 attemptRecordPresent=true providerReceiptObserved=false");
                        return result;
                    }));
        } finally {
            server.stop(0);
        }
    }


    @Test
    void tavilyAttemptRowsPreserveResponseStatusCountsAndFailures() {
        record Case(int status, String body, String reason, Object returned, int after) { }
        for (Case scenario : List.of(
                new Case(200, "{\"results\":[{}, {\"content\":\"safe\"}]}", "none", 2, 1),
                new Case(201, "{\"results\":[{\"content\":\"safe\"}]}", "none", 1, 1),
                new Case(204, "", "provider-empty", 0, 0),
                new Case(200, "{\"results\":[]}", "provider-empty", 0, 0),
                new Case(200, "{\"results\":[{}]}", "after-filter-starvation", 1, 0),
                new Case(200, "{", "exception", "unknown", 0),
                new Case(429, "synthetic error body", "rate-limit", "unknown", 0),
                new Case(503, "synthetic error body", "http-error", "unknown", 0))) {
            TraceStore.clear();
            var exchanges = new AtomicInteger();
            TavilyWebSearchRetriever retriever = tavilyAttemptFixture(WebClient.builder().exchangeFunction(request -> {
                exchanges.incrementAndGet();
                ClientResponse.Builder response = ClientResponse.create(org.springframework.http.HttpStatusCode.valueOf(scenario.status()))
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                if (scenario.status() != 204) response.body(scenario.body());
                return Mono.just(response.build());
            }));
            var result = retriever.retrieve(new Query("synthetic tavily status"));
            assertEquals(scenario.after(), result.size());
            assertEquals(1, exchanges.get());
            Map<?, ?> row = onlyTavilyAttempt();
            assertEquals(scenario.status(), row.get("httpStatus"));
            assertEquals(scenario.returned(), row.get("returnedCount"), "status=" + scenario.status());
            assertEquals(scenario.returned() instanceof Integer ? scenario.after() : "unknown", row.get("afterFilterCount"));
            assertEquals(scenario.reason(), row.get("failureReason"));
            assertEquals("unknown".equals(scenario.returned()) ? "ERROR" : scenario.after() > 0 ? "OK" : "EMPTY", row.get("outcome"));
            assertFalse(row.toString().contains("synthetic error body"));
        }
    }

    @Test
    void tavilyAttemptDoesNotStartBeforeDisabledBlankOrClientSetupGates() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> { throw new AssertionError("pre-exchange gate leaked"); });
        for (int gate = 0; gate < 3; gate++) {
            TraceStore.clear();
            TavilyWebSearchRetriever retriever = tavilyAttemptFixture(builder);
            if (gate == 1) ReflectionTestUtils.setField(retriever, "apiKey", "");
            if (gate == 2) ReflectionTestUtils.setField(retriever, "baseUrl", "http://[");
            assertTrue(retriever.retrieve(gate == 0 ? null : new Query("synthetic gate")).isEmpty());
            assertEquals(null, TraceStore.get("web.tavily.attempt.runs"));
            assertEquals(null, TraceStore.get("providerAttemptId"));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void tavilyAttemptRestoresSubscriberContextBeforeSignalsAndOnCancellation() {
        for (String mode : List.of("success", "cancelled", "timeout")) {
            TraceStore.clear();
            var entryIds = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            var subscriberIds = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var entryThread = new java.util.concurrent.atomic.AtomicLong();
            var sameSubscriberThread = new java.util.concurrent.atomic.AtomicBoolean();
            WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
                entryIds.set(TraceStore.searchCorrelation(TraceStore.context()));
                entryThread.set(Thread.currentThread().getId());
                return Mono.defer(() -> {
                    subscriberIds.set(TraceStore.searchCorrelation(TraceStore.context()));
                    sameSubscriberThread.set(entryThread.get() == Thread.currentThread().getId());
                    return switch (mode) {
                        case "cancelled" -> Mono.<ClientResponse>error(new java.util.concurrent.CancellationException("synthetic cancellation"));
                        case "timeout" -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
                        default -> Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body("{\"results\":[{\"content\":\"safe\"}]}").build());
                    };
                });
            });
            var retriever = tavilyAttemptFixture(builder);
            ReflectionTestUtils.setField(retriever, "timeoutMs", 1000);
            TraceStore.withSearchContext("retrievalExecutionId", () -> TraceStore.withSearchContext("searchExecutionId", () -> {
                Map<String, Object> callerIds = TraceStore.searchCorrelation(TraceStore.context());
                var result = retriever.retrieve(new Query("synthetic reactive outcome"));
                assertEquals("success".equals(mode) ? 1 : 0, result.size());
                assertEquals(callerIds, TraceStore.searchCorrelation(TraceStore.context()));
                assertEquals(3, entryIds.get().size());
                assertEquals(Map.of(), subscriberIds.get(), "attempt context must leave the subscriber thread before later signals");
                assertTrue(sameSubscriberThread.get());
                Map<?, ?> row = onlyTavilyAttempt();
                entryIds.get().forEach((key, value) -> assertEquals(value, row.get(key)));
                assertEquals("success".equals(mode) ? "none" : mode, row.get("failureReason"));
                if ("timeout".equals(mode)) assertTrue(cancelled.get());
                return result;
            }));
            builder.filters(filters -> assertTrue(filters.isEmpty(), "attempt filters must not accumulate on the shared builder"));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void tavilyAttemptSeparatesConcurrentOpposedOutcomes() throws Exception {
        var parent = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        var scope = TraceStore.searchContext(TraceStore.searchContext(parent, "retrievalExecutionId"), "searchExecutionId");
        var entered = new java.util.concurrent.CountDownLatch(2);
        var ordinal = new AtomicInteger();
        var expected = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            int status = ordinal.getAndIncrement() == 0 ? 200 : 429;
            Object id = TraceStore.searchCorrelation(TraceStore.context()).get("providerAttemptId");
            if (id != null) expected.put(id.toString(), status);
            entered.countDown();
            try { assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatusCode.valueOf(status))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("{\"results\":[{\"content\":\"safe\"}]}").build());
        });
        var retriever = tavilyAttemptFixture(builder);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { TraceStore.installContext(scope); try { return retriever.retrieve(new Query("synthetic concurrent A")); } finally { TraceStore.clear(); } });
            var second = workers.submit(() -> { TraceStore.installContext(scope); try { return retriever.retrieve(new Query("synthetic concurrent B")); } finally { TraceStore.clear(); } });
            assertEquals(1, first.get(5, java.util.concurrent.TimeUnit.SECONDS).size() + second.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
            assertEquals(2, ordinal.get());
            var rows = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, parent.get("web.tavily.attempt.runs"));
            assertEquals(2, rows.size());
            assertEquals(2, expected.size());
            var hashes = new java.util.HashSet<Object>();
            for (Object value : rows) {
                Map<?, ?> row = (Map<?, ?>) value;
                int status = expected.get(row.get("providerAttemptId"));
                assertEquals(status, row.get("httpStatus"));
                assertEquals(status == 200 ? 1 : "unknown", row.get("returnedCount"));
                assertEquals(status == 200 ? "none" : "rate-limit", row.get("failureReason"));
                hashes.add(row.get("queryHash"));
            }
            assertEquals(java.util.Set.of(com.example.lms.trace.SafeRedactor.hashValue("synthetic concurrent A"),
                    com.example.lms.trace.SafeRedactor.hashValue("synthetic concurrent B")), hashes);
            builder.filters(filters -> assertTrue(filters.isEmpty()));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void tavilyAttemptStorageIsBoundedAndImmutable() {
        TraceStore.clear();
        var mutable = new java.util.HashMap<String, Object>(); mutable.put("ordinal", 0);
        TraceStore.append("web.tavily.attempt.runs", mutable); mutable.put("ordinal", -1);
        var firstSnapshot = (List<?>) TraceStore.get("web.tavily.attempt.runs");
        assertEquals(0, ((Map<?, ?>) firstSnapshot.get(0)).get("ordinal"));
        for (int i = 1; i < 130; i++) TraceStore.append("web.tavily.attempt.runs", Map.of("ordinal", i));
        var rows = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, TraceStore.get("web.tavily.attempt.runs"));
        assertEquals(128, rows.size());
        assertEquals(2, ((Map<?, ?>) rows.get(0)).get("ordinal"), "existing storage retains the newest 128 rows");
        assertEquals(1, firstSnapshot.size());
        assertEquals(0, ((Map<?, ?>) firstSnapshot.get(0)).get("ordinal"));
        assertEquals(2L, TraceStore.getLong("web.tavily.attempt.runs.dropped"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, rows::clear);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) rows.get(0)).clear());
    }

    @Test
    void tavilyAttemptClosedSinkPreservesResultAndCaller() {
        var parent = new java.util.concurrent.ConcurrentHashMap<String, Object>() {
            @Override public Object compute(String key, java.util.function.BiFunction<? super String, ? super Object, ?> action) {
                if ("web.tavily.attempt.runs".equals(key)) throw new UnsupportedOperationException("synthetic closed sink");
                return super.compute(key, action);
            }
        };
        TraceStore.installContext(parent);
        var retriever = tavilyAttemptFixture(WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("{\"results\":[{\"content\":\"safe\"}]}").build())));
        assertEquals(1, retriever.retrieve(new Query("synthetic closed trace")).size());
        org.junit.jupiter.api.Assertions.assertSame(parent, TraceStore.context());
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void tavilyAttemptPreservesInterruptedCallerAndCancelsSubscription() throws Exception {
        var parent = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var retriever = tavilyAttemptFixture(WebClient.builder().exchangeFunction(request ->
                Mono.<ClientResponse>never().doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(cancelled::countDown)));
        Thread caller = new Thread(() -> {
            TraceStore.installContext(parent);
            try {
                assertTrue(retriever.retrieve(new Query("synthetic interrupt")).isEmpty());
                interrupted.set(Thread.currentThread().isInterrupted());
                org.junit.jupiter.api.Assertions.assertSame(parent, TraceStore.context());
            } catch (Throwable error) { failure.set(error); }
            finally { TraceStore.clear(); }
        }, "sec08-tavily-interrupt");
        try {
            caller.start(); assertTrue(subscribed.await(3, java.util.concurrent.TimeUnit.SECONDS)); caller.interrupt(); caller.join(3000);
            assertFalse(caller.isAlive()); assertEquals(null, failure.get()); assertTrue(interrupted.get());
            assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var rows = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, parent.get("web.tavily.attempt.runs"));
            assertEquals(1, rows.size()); assertEquals("cancelled", ((Map<?, ?>) rows.get(0)).get("failureReason"));
        } finally {
            caller.interrupt(); caller.join(3000);
        }
    }

    private static TavilyWebSearchRetriever tavilyAttemptFixture(WebClient.Builder builder) {
        var retriever = new TavilyWebSearchRetriever(builder);
        ReflectionTestUtils.setField(retriever, "apiKey", "fixture-tavily-lineage-key");
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://example.invalid/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 2);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 4000);
        return retriever;
    }

    private static Map<?, ?> onlyTavilyAttempt() {
        var rows = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, TraceStore.get("web.tavily.attempt.runs"));
        assertEquals(1, rows.size());
        return org.junit.jupiter.api.Assertions.assertInstanceOf(Map.class, rows.get(0));
    }

    @Test
    void missingApiKeyUsesCanonicalProviderDisabledReason() {
        TraceStore.clear();
        String rawQuery = "private tavily missing key query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("missing api key must not call Tavily");
                }));
        ReflectionTestUtils.setField(retriever, "apiKey", "");
        ReflectionTestUtils.setField(retriever, "maxResults", 2);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReason"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReasonCanonical"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.skipped.reason"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertFalse(TraceStore.getAll().toString().contains(rawQuery));
    }

    private static final class ReturningRetriever extends TavilyWebSearchRetriever {
        ReturningRetriever() {
            super(WebClient.builder());
        }

        @Override
        public List<Content> retrieve(Query query) {
            return List.of(Content.from(TextSegment.from(
                    "retrieved tavily snippet",
                    Metadata.from(Map.of(
                            "title", "Tavily Result",
                            "url", "https://example.com/tavily"
                    ))
            )));
        }
    }

    private static final class EmptyRetriever extends TavilyWebSearchRetriever {
        EmptyRetriever() {
            super(WebClient.builder());
        }

        @Override
        public List<Content> retrieve(Query query) {
            return List.of();
        }
    }
}
