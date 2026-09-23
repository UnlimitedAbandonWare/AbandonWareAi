package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SerpApiProviderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void topKIsClampedBeforeUriAndAfterParsing() {
        SerpApiProvider provider = enabledProvider();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AtomicReference<String> outboundNum = new AtomicReference<>();
        server.expect(request -> outboundNum.set(UriComponentsBuilder.fromUri(request.getURI())
                        .build()
                        .getQueryParams()
                        .getFirst("num")))
                .andRespond(withSuccess(organicResultsJson(80), MediaType.APPLICATION_JSON));

        var result = provider.search(new WebSearchQuery(
                "bounded serpapi query",
                Integer.MAX_VALUE,
                null,
                null));

        server.verify();
        assertEquals("20", outboundNum.get());
        assertEquals(20, result.getDocuments().size());
        assertEquals(20, TraceStore.get("web.serpapi.requestedCount"));
        assertEquals(20, TraceStore.get("web.serpapi.returnedCount"));
    }

    @Test
    void configuredMaximumUsesDefaultAndProviderBounds() {
        SerpApiProvider provider = new SerpApiProvider();

        assertEquals(20, ((Number) ReflectionTestUtils.invokeMethod(provider, "configuredMaximum")).intValue());
        ReflectionTestUtils.setField(provider, "maxResults", 0);
        assertEquals(1, ((Number) ReflectionTestUtils.invokeMethod(provider, "configuredMaximum")).intValue());
        ReflectionTestUtils.setField(provider, "maxResults", 101);
        assertEquals(100, ((Number) ReflectionTestUtils.invokeMethod(provider, "configuredMaximum")).intValue());
    }

    @Test
    void zeroAndNegativeTopKReturnEmptyWithoutOutboundCall() {
        SerpApiProvider provider = enabledProvider();
        AtomicInteger attempts = installRejectingTransport(provider);

        assertTrue(provider.search(new WebSearchQuery("zero serpapi query", 0, null, null))
                .getDocuments()
                .isEmpty());
        assertTrue(provider.search(new WebSearchQuery("negative serpapi query", -1, null, null))
                .getDocuments()
                .isEmpty());

        assertEquals(0, attempts.get());
    }

    @Test
    void missingDummyAndPlaceholderCredentialsStayDisabledWithoutOutboundCall() {
        for (String configuredValue : List.of("", "test", "changeme", "${SERPAPI_API_KEY}")) {
            SerpApiProvider provider = configuredProvider(configuredValue);
            AtomicInteger attempts = installRejectingTransport(provider);

            var result = provider.search(new WebSearchQuery("disabled serpapi query", 5, null, null));

            assertFalse(provider.isEnabled());
            assertEquals("missing_serpapi_api_key", provider.disabledReason());
            assertTrue(result.getDocuments().isEmpty());
            assertEquals(0, attempts.get());
        }
    }


    @Test
    @org.junit.jupiter.api.Timeout(10)
    void loopbackReceiptAndOutcomeJoinRequestScopedAttempt() throws Exception {
        TraceStore.clear();
        String rawQuery = "sec08 synthetic serpapi document";
        String key = "fixture-serpapi-lineage-key";
        AtomicInteger receipts = new AtomicInteger();
        AtomicReference<String> receivedQueryHash = new AtomicReference<>();
        var shapeMatches = new java.util.concurrent.atomic.AtomicBoolean();
        var boundaryIds = new AtomicReference<java.util.Map<String, Object>>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search.json", exchange -> {
            try {
                receipts.incrementAndGet();
                var params = UriComponentsBuilder.fromUri(exchange.getRequestURI()).build().getQueryParams();
                String query = java.net.URLDecoder.decode(params.getFirst("q"), java.nio.charset.StandardCharsets.UTF_8);
                receivedQueryHash.set(com.example.lms.trace.SafeRedactor.hashValue(query));
                shapeMatches.set("GET".equals(exchange.getRequestMethod()) && "google".equals(params.getFirst("engine"))
                        && key.equals(params.getFirst("api_key")) && "3".equals(params.getFirst("num")));
                byte[] response = organicResultsJson(1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            SerpApiProvider provider = configuredProvider(key);
            ReflectionTestUtils.setField(provider, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/search.json");
            RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
            template.getInterceptors().add((request, body, execution) -> {
                boundaryIds.set(TraceStore.searchCorrelation(TraceStore.context()));
                return execution.execute(request, body);
            });
            TraceStore.withSearchContext("retrievalExecutionId", () -> TraceStore.withSearchContext("searchExecutionId", () -> {
                var callerIds = TraceStore.searchCorrelation(TraceStore.context());
                assertEquals(2, callerIds.size());
                var result = provider.search(new WebSearchQuery(rawQuery, 3, null, null));
                assertEquals(1, result.getDocuments().size());
                assertEquals(1, receipts.get());
                assertTrue(shapeMatches.get());
                assertEquals(receivedQueryHash.get(), TraceStore.get("web.serpapi.queryHash"));
                assertEquals(1, TraceStore.get("web.serpapi.returnedCount"));
                assertEquals(1, TraceStore.get("web.serpapi.afterFilterCount"));
                assertEquals(3, boundaryIds.get().size());
                callerIds.forEach((k, v) -> assertEquals(v, boundaryIds.get().get(k)));
                var attempt = singleAttempt();
                boundaryIds.get().forEach((k, v) -> assertEquals(v, attempt.get(k)));
                assertEquals(receivedQueryHash.get(), attempt.get("queryHash"));
                assertEquals(200, attempt.get("httpStatus"));
                assertEquals("OK", attempt.get("outcome"));
                assertEquals(1, attempt.get("returnedCount"));
                assertEquals(1, attempt.get("afterFilterCount"));
                assertEquals("admitted_document_list", attempt.get("countScope"));
                assertEquals(false, attempt.get("providerReceiptObserved"));
                assertEquals(callerIds, TraceStore.searchCorrelation(TraceStore.context()));
                assertEquals(null, TraceStore.get("providerAttemptId"));
                assertFalse(TraceStore.getAll().toString().contains(rawQuery));
                assertFalse(TraceStore.getAll().toString().contains(key));
                System.out.println("SEC08_SERPAPI logicalScopes=2 clientBoundaryScopes=3 loopbackReceipts=1 returned=1 attemptRecordPresent=true providerReceiptObserved=false");
                return result;
            }));
        } finally { server.stop(0); }
    }


    @Test
    void responseMatrixKeepsActualStatusAndAdmittedCountScope() {
        for (String mode : List.of("created", "no-content", "missing-array", "all-filtered", "malformed")) {
            TraceStore.clear();
            int status = mode.equals("created") ? 201 : mode.equals("no-content") ? 204 : 200;
            String body = switch(mode) {
                case "created" -> organicResultsJson(30);
                case "missing-array" -> "{}";
                case "all-filtered" -> "{\"organic_results\":[{\"title\":\"\",\"link\":\"https://example.test\"}]}";
                case "malformed" -> "{";
                default -> null;
            };
            var provider = enabledProvider();
            var server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(provider,"restTemplate")).build();
            var response = org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.valueOf(status));
            if (body != null) response.body(body).contentType(MediaType.APPLICATION_JSON);
            server.expect(request -> {}).andRespond(response);
            var before = TraceStore.context();
            var result = provider.search(new WebSearchQuery("synthetic response "+mode, 20, null, null));
            assertSame(before, TraceStore.context()); server.verify();
            var row = singleAttempt();
            assertEquals(status,row.get("httpStatus"),mode);
            assertEquals("admitted_document_list",row.get("countScope"));
            assertEquals(true,row.get("clientAttemptObserved"));
            assertEquals("resttemplate_get",row.get("clientAttemptBoundary"));
            assertEquals(false,row.get("providerReceiptObserved"));
            assertTrue(((Number)row.get("elapsedMs")).longValue()>=0);
            assertTrue(((Number)row.get("finishedAtEpochMs")).longValue()>=((Number)row.get("startedAtEpochMs")).longValue());
            int expected = mode.equals("created") ? 20 : 0;
            assertEquals(expected,result.getDocuments().size());
            if(mode.equals("malformed")) {
                assertEquals("ERROR",row.get("outcome")); assertEquals("parse-error",row.get("failureReason"));
                assertEquals("unknown",row.get("returnedCount")); assertEquals("unknown",row.get("afterFilterCount"));
            } else {
                assertEquals(expected,row.get("returnedCount")); assertEquals(expected,row.get("afterFilterCount"));
                assertEquals(expected==0 ? "EMPTY" : "OK",row.get("outcome"));
                assertEquals(expected==0 ? "provider_empty" : "none",row.get("failureReason"));
            }
        }
    }

    @Test
    void failureMatrixRestoresCallerAndKeepsUnavailableCountsUnknown() {
        for(String mode:List.of("429","503","timeout","cancelled","exception")) {
            TraceStore.clear();
            var provider=enabledProvider();
            var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(provider,"restTemplate")).build();
            if(mode.equals("429") || mode.equals("503")) {
                server.expect(request -> {}).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.valueOf(Integer.parseInt(mode))).body("synthetic-private-error-body"));
            } else {
                server.expect(request -> {}).andRespond(request -> {
                    if(mode.equals("timeout")) throw new org.springframework.web.client.ResourceAccessException("synthetic",new java.net.SocketTimeoutException());
                    if(mode.equals("cancelled")) throw new org.springframework.web.client.ResourceAccessException("synthetic",new IOException(new InterruptedException()));
                    throw new IllegalStateException("synthetic-private-error-body");
                });
            }
            TraceStore.withSearchContext("searchExecutionId",()-> {
                var before=TraceStore.context();var ids=TraceStore.searchCorrelation(before);
                assertTrue(provider.search(new WebSearchQuery("synthetic failure",3,null,null)).getDocuments().isEmpty());
                assertSame(before,TraceStore.context());var row=singleAttempt();
                ids.forEach((k,v)->assertEquals(v,row.get(k)));assertNull(TraceStore.get("providerAttemptId"));
                assertEquals("ERROR",row.get("outcome"));
                assertEquals(mode.equals("429") ? "rate-limit" : mode.equals("503") ? "http-error" : mode,row.get("failureReason"));
                assertEquals(mode.equals("429") || mode.equals("503") ? Integer.parseInt(mode) : "unknown",row.get("httpStatus"));
                assertEquals("unknown",row.get("returnedCount"));assertEquals("unknown",row.get("afterFilterCount"));
                assertFalse(row.toString().contains("synthetic-private-error-body"));return true;
            });
            server.verify();Thread.interrupted();
        }
    }

    @Test
    void blankDisabledZeroAndMalformedUriProduceNoAttempt() {
        for(String mode:List.of("blank","disabled","zero","invalid-uri")) {
            TraceStore.clear();var provider=mode.equals("disabled")?configuredProvider("test"):enabledProvider();
            var requests=installRejectingTransport(provider);
            if(mode.equals("invalid-uri")) ReflectionTestUtils.setField(provider,"baseUrl","invalid");
            var before=TraceStore.context();
            var result=provider.search(new WebSearchQuery(mode.equals("blank")?" ":"synthetic gate",mode.equals("zero")?0:3,null,null));
            assertTrue(result.getDocuments().isEmpty());assertEquals(0,requests.get());
            assertNull(TraceStore.get("web.serpapi.attempt.runs"));assertNull(TraceStore.get("providerAttemptId"));assertSame(before,TraceStore.context());
        }
    }

    @Test
    void attemptsAreBoundedAndRowsAndSnapshotsAreImmutable() {
        var mutable=new java.util.HashMap<String,Object>();mutable.put("ordinal",0);
        TraceStore.append("web.serpapi.attempt.runs",mutable);
        var snapshot=assertInstanceOf(List.class,TraceStore.get("web.serpapi.attempt.runs"));
        mutable.put("ordinal",999);
        assertEquals(0,((java.util.Map<?,?>)snapshot.get(0)).get("ordinal"));
        for(int i=1;i<130;i++) TraceStore.append("web.serpapi.attempt.runs",java.util.Map.of("ordinal",i));
        var rows=assertInstanceOf(List.class,TraceStore.get("web.serpapi.attempt.runs"));
        assertEquals(128,rows.size());assertEquals(2L,TraceStore.getLong("web.serpapi.attempt.runs.dropped"));
        assertEquals(2,((java.util.Map<?,?>)rows.get(0)).get("ordinal"));assertEquals(1,snapshot.size());
        assertThrows(UnsupportedOperationException.class,()->rows.clear());
        assertThrows(UnsupportedOperationException.class,()->((java.util.Map<?,?>)rows.get(0)).clear());
    }

    @Test
    void closedAttemptSinkDoesNotReplaceSuccessfulResult() {
        var parent=new java.util.concurrent.ConcurrentHashMap<String,Object>() {
            @Override public Object compute(String key,java.util.function.BiFunction<? super String,? super Object,?> f) {
                if("web.serpapi.attempt.runs".equals(key)) throw new UnsupportedOperationException("synthetic closed sink");
                return super.compute(key,f);
            }
        };
        TraceStore.installContext(parent);
        var provider=enabledProvider();var server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(provider,"restTemplate")).build();
        server.expect(request -> {}).andRespond(withSuccess(organicResultsJson(1),MediaType.APPLICATION_JSON));
        assertEquals(1,provider.search(new WebSearchQuery("synthetic closed sink",3,null,null)).getDocuments().size());
        assertSame(parent,TraceStore.context());assertNull(TraceStore.get("providerAttemptId"));server.verify();
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void overlappingSearchesKeepOwnedCountsAndBindings() throws Exception {
        var parent=new java.util.concurrent.ConcurrentHashMap<String,Object>();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks=new java.util.ArrayList<java.util.concurrent.Callable<java.util.Map<String,Object>>>();
            for(int count:List.of(1,2)) tasks.add(()-> {
                TraceStore.installContext(parent);
                try {
                    return TraceStore.withSearchContext("retrievalExecutionId",()->TraceStore.withSearchContext("searchExecutionId",()-> {
                        var before=TraceStore.context();var boundary=new AtomicReference<java.util.Map<String,Object>>();
                        var provider=enabledProvider();var template=(RestTemplate)ReflectionTestUtils.getField(provider,"restTemplate");
                        template.getInterceptors().add((request,body,execution)-> {
                            boundary.set(TraceStore.searchCorrelation(TraceStore.context()));
                            try {barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new IOException("synthetic barrier failure",e);}
                            return execution.execute(request,body);
                        });
                        var server=MockRestServiceServer.bindTo(template).build();server.expect(request -> {}).andRespond(withSuccess(organicResultsJson(count),MediaType.APPLICATION_JSON));
                        assertEquals(count,provider.search(new WebSearchQuery("synthetic overlap "+count,3,null,null)).getDocuments().size());
                        assertSame(before,TraceStore.context());server.verify();
                        var expected=new java.util.HashMap<String,Object>(boundary.get());expected.put("queryHash",com.example.lms.trace.SafeRedactor.hashValue("synthetic overlap "+count));expected.put("returnedCount",count);return expected;
                    }));
                } finally { TraceStore.clear(); }
            });
            var futures=workers.invokeAll(tasks);
            var rows=assertInstanceOf(List.class,parent.get("web.serpapi.attempt.runs"));assertEquals(2,rows.size());
            for(var future:futures) {
                var expected=future.get();assertEquals(5,expected.size());
                assertTrue(rows.stream().anyMatch(value->expected.entrySet().stream().allMatch(e->e.getValue().equals(((java.util.Map<?,?>)value).get(e.getKey())))));
            }
            assertFalse(((java.util.Map<?,?>)rows.get(0)).get("providerAttemptId").equals(((java.util.Map<?,?>)rows.get(1)).get("providerAttemptId")));
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> singleAttempt() {
        var rows=assertInstanceOf(List.class,TraceStore.get("web.serpapi.attempt.runs"));assertEquals(1,rows.size());
        var row=(java.util.Map<String,Object>)rows.get(0);
        assertTrue(row.keySet().stream().allMatch(java.util.Set.of("retrievalExecutionId","searchExecutionId","providerAttemptId","provider","queryHash","countScope","clientAttemptObserved","clientAttemptBoundary","providerReceiptObserved","startedAtEpochMs","finishedAtEpochMs","elapsedMs","httpStatus","returnedCount","afterFilterCount","failureReason","outcome")::contains));
        assertEquals("serpapi",row.get("provider"));
        return row;
    }

    private static SerpApiProvider enabledProvider() {
        return configuredProvider("sk-serpbound000059");
    }

    private static SerpApiProvider configuredProvider(String apiKey) {
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "configEnabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "baseUrl", "https://serpapi.com/search.json");
        ReflectionTestUtils.setField(provider, "timeoutMs", 2_000);
        ReflectionTestUtils.invokeMethod(provider, "init");
        return provider;
    }

    private static AtomicInteger installRejectingTransport(SerpApiProvider provider) {
        AtomicInteger attempts = new AtomicInteger();
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        restTemplate.setRequestFactory((uri, method) -> {
            attempts.incrementAndGet();
            throw new IOException("unexpected outbound call");
        });
        return attempts;
    }

    private static String organicResultsJson(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"title\":\"result-" + i
                        + "\",\"link\":\"https://example.com/" + i
                        + "\",\"snippet\":\"usable result " + i + "\"}")
                .collect(Collectors.joining(",", "{\"organic_results\":[", "]}"));
    }
}
