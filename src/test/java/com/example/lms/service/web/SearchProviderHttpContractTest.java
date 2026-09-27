package com.example.lms.service.web;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.transform.QueryTransformer;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real socket/HTTP clients and parsers. Only the external provider HTTP boundary is substituted. */
class SearchProviderHttpContractTest {
    private static final String BRAVE_BODY = "{\"web\":{\"results\":[{\"title\":\"Brave fixture\",\"url\":\"https://example.test/brave\"}]}}";
    private static final String NAVER_BODY = "{\"items\":[{\"title\":\"Naver fixture\",\"link\":\"https://example.test/naver\",\"description\":\"synthetic document\"}]}";

    @BeforeAll static void initializeHttpRuntimeOutsideRequestBudgets() {
        // Native/event-loop cold initialization is not provider latency in the warmed application.
        reactor.netty.http.client.HttpClient.create().warmup().block(Duration.ofSeconds(5));
    }

    @BeforeEach @AfterEach void clear() {
        TimeBudgetContext.clear(); TraceStore.clear(); Thread.interrupted();
        com.example.lms.trace.TraceContext.cleanupCurrentThread();
        com.example.lms.service.guard.GuardContextHolder.clear();
    }

    @Test void actualHybridEntryUsesBothRealClientsThenMergesParsedResults() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            var workers = Executors.newFixedThreadPool(2);
            HybridWebSearchProvider hybrid = new HybridWebSearchProvider(naver(wire, "fixtureid:fixturesecret"), brave(wire));
            ReflectionTestUtils.setField(hybrid, "primary", "BRAVE");
            ReflectionTestUtils.setField(hybrid, "koreanHedgeDelayMs", 0L);
            ReflectionTestUtils.setField(hybrid, "timeoutSec", 5);
            ReflectionTestUtils.setField(hybrid, "searchIoExecutor", workers);
            try {
                List<String> result = hybrid.search("합성 문서", 3);
                assertEquals(2, result.size(), () -> "wireCount=" + wire.requests.size()
                        + " naverClass=" + TraceStore.getString("web.naver.failureClass")
                        + " naverReason=" + TraceStore.getString("web.naver.failureReason")
                        + " awaitTimeout=" + TraceStore.getLong("web.await.events.timeout.count"));
                assertTrue(result.get(0).contains("https://example.test/brave"));
                // Existing Naver public snippet facade strips anchor markup (including its href).
                assertEquals("- Naver fixture: synthetic document", result.get(1));
                assertEquals(2, wire.requests.size());
                assertTrue(TraceStore.getLong("web.hybrid.execution.submitted") >= 2);
                var stages = (List<?>) TraceStore.get("web.naver.filter.runs");
                assertNotNull(stages);
                var stage = (Map<?, ?>) stages.get(stages.size() - 1);
                assertNotNull(stage.get("searchExecutionId"), "each provider stage must join its exact merge");
                assertNotNull(stage.get("providerAttemptId"), "HTTP retries must have distinct identities");
                assertEquals(1, stage.get("afterFilterCount"));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void naverRequestUsesUtf8RequiredHeadersAndBoundedDisplayFirstPage() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            NaverSearchService service = naver(wire, "fixtureid:fixturesecret");
            assertEquals(1, service.searchWithTraceSync("합성 문서", 500, Duration.ofSeconds(5)).snippets().size());
            Captured request = wire.requests.get(0);
            assertEquals("GET", request.method());
            assertEquals("/v1/search/webkr.json", request.uri().getPath());
            assertEquals("합성 문서", query(request.uri(), "query"));
            assertEquals("1", query(request.uri(), "start"));
            assertTrue(Integer.parseInt(query(request.uri(), "display")) <= 100);
            assertEquals("fixtureid", request.headers().getFirst("X-Naver-Client-Id"));
            assertEquals("fixturesecret", request.headers().getFirst("X-Naver-Client-Secret"));
            assertEquals(1, wire.requests.size());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"normal", "zero", "malformed", "mapping"})
    void realNaverAdapterStagesJoinHybridMergeAndFinalRetrievalSnapshot(String mode) throws Exception {
        String naverBody = switch (mode) {
            case "zero" -> "{\"total\":98765,\"items\":[]}";
            case "malformed" -> "{malformed";
            case "mapping" -> "{\"items\":[42]}";
            default -> NAVER_BODY.replace("{\"items\"", "{\"total\":98765,\"items\"");
        };
        String braveBody = "normal".equals(mode) ? BRAVE_BODY : "{\"web\":{\"results\":[]}}";
        try (Fixture wire = new Fixture(200, braveBody, naverBody, Map.of())) {
            var workers = Executors.newFixedThreadPool(2);
            HybridWebSearchProvider hybrid = new HybridWebSearchProvider(naver(wire, "fixtureid:fixturesecret"), brave(wire));
            ReflectionTestUtils.setField(hybrid, "primary", "BRAVE");
            ReflectionTestUtils.setField(hybrid, "koreanHedgeDelayMs", 0L);
            ReflectionTestUtils.setField(hybrid, "timeoutSec", 8);
            ReflectionTestUtils.setField(hybrid, "searchIoExecutor", workers);
            var authority = mock(com.example.lms.service.rag.auth.AuthorityScorer.class);
            var detector = mock(com.example.lms.service.rag.detector.GameDomainDetector.class);
            when(detector.detect(anyString())).thenReturn("GENERAL");
            var retriever = new com.example.lms.service.rag.WebSearchRetriever(hybrid, null,
                    mock(com.example.lms.service.rag.extract.PageContentScraper.class), authority,
                    mock(com.example.lms.service.rag.filter.GenericDocClassifier.class), detector,
                    mock(com.example.lms.service.rag.filter.EducationDocClassifier.class));
            ReflectionTestUtils.setField(retriever, "topK", 3);
            try {
                var result = retriever.retrieve(dev.langchain4j.rag.query.Query.from("합성 연결 문서"));
                var stages = (List<?>) TraceStore.get("web.naver.filter.runs");
                assertNotNull(stages);
                assertFalse(stages.isEmpty());
                var events = (List<?>) TraceStore.get("orch.events.v1");
                assertNotNull(events);
                var selected = events.stream().map(item -> (Map<?,?>)item)
                        .filter(e -> "web_retrieval".equals(e.get("stage"))).reduce((a,b) -> b).orElseThrow();
                assertEquals(result.size(), ((Map<?,?>)selected.get("output")).get("selectedCount"));
                assertNotNull(selected.get("retrievalExecutionId"));
                for (Object value : stages) {
                    var row = (Map<?,?>) value;
                    assertEquals(selected.get("retrievalExecutionId"), row.get("retrievalExecutionId"));
                    assertNotNull(row.get("providerAttemptId"));
                    assertTrue(events.stream().map(item -> (Map<?,?>)item).anyMatch(event ->
                            "web_merge".equals(event.get("stage"))
                                    && row.get("searchExecutionId").equals(event.get("searchExecutionId"))));
                    assertEquals("single_response_items_array", row.get("countScope"));
                    assertEquals(false, row.get("providerReceiptObserved"));
                    if ("mapping".equals(mode)) {
                        assertEquals(1, row.get("rawSize"));
                        assertEquals("unknown", row.get("parsedCount"));
                        assertEquals("ITEM_MAPPING_ERROR", row.get("failureClass"));
                    } else if ("malformed".equals(mode)) {
                        assertEquals("unknown", row.get("rawSize"));
                        assertEquals("PARSE_ERROR", row.get("failureClass"));
                    } else {
                        int expected = "zero".equals(mode) ? 0 : 1;
                        assertEquals(expected, row.get("rawSize"), "provider total must not replace returned items size");
                        assertEquals(expected, row.get("parsedCount"));
                        assertEquals(expected, row.get("afterFilterCount"));
                    }
                }
                if ("normal".equals(mode)) assertEquals(2, wire.requests.size(), "instrumentation must not resubscribe HTTP");
                var snapshots = new com.example.lms.trace.TraceSnapshotStore(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.example.lms.service.trace.TraceHtmlBuilder.class));
                ReflectionTestUtils.setField(snapshots, "enabled", true);
                ReflectionTestUtils.setField(snapshots, "htmlEnabled", false);
                ReflectionTestUtils.setField(snapshots, "maxSize", 20);
                ReflectionTestUtils.setField(snapshots, "maxValueLen", 2000);
                ReflectionTestUtils.setField(snapshots, "maxEntries", 300);
                for (String field : List.of("allowReasonsCsv", "denyReasonsCsv", "allowKeysCsv", "denyKeysCsv"))
                    ReflectionTestUtils.setField(snapshots, field, "");
                ReflectionTestUtils.setField(snapshots, "allowKeysMode", "any");
                ReflectionTestUtils.setField(snapshots, "captureSample", 1.0d);
                ReflectionTestUtils.setField(snapshots, "httpStatusMin", 400);
                ReflectionTestUtils.setField(snapshots, "maxPerTrace", 10);
                ReflectionTestUtils.setField(snapshots, "budgetWindowMs", 600_000L);
                String id = snapshots.captureCurrent("llm_failure", "POST", "/api/chat/stream", 500, null);
                assertNotNull(id);
                var snapshot = snapshots.get(id).orElseThrow();
                assertTrue(snapshot.orchestration().toString().contains(selected.get("retrievalExecutionId").toString()),
                        "snapshot allowlist must preserve the join identity");
                String publicTrace = TraceStore.getAll().toString();
                assertFalse(publicTrace.contains("합성 연결 문서"));
                assertFalse(publicTrace.contains("fixturesecret"));
                assertFalse(publicTrace.contains(naverBody));
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void braveRequestRetainsFullPathUtf8CountAndFirstPageWithOptionalDescriptionOmitted() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            BraveSearchResult result = brave(wire).searchWithMeta("합성 문서", 500);
            assertEquals(BraveSearchResult.Status.OK, result.status());
            assertEquals(1, result.snippets().size());
            Captured request = wire.requests.get(0);
            assertEquals("GET", request.method());
            assertEquals("/res/v1/web/search", request.uri().getPath());
            assertEquals("합성 문서", query(request.uri(), "q"));
            assertEquals("20", query(request.uri(), "count"));
            assertNull(query(request.uri(), "offset"), "existing adapter intentionally retrieves first page only");
            assertEquals("fixture-brave-key", request.headers().getFirst("X-Subscription-Token"));
        }
    }

    @Test void braveLoopbackReceiptAndOutcomeCarryTheSameRequestScopedAttempt() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            BraveSearchService service = brave(wire);
            var boundaryIds = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
            var template = (org.springframework.web.client.RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
            var interceptors = new java.util.ArrayList<>(template.getInterceptors());
            interceptors.add((request, body, execution) -> {
                boundaryIds.set(TraceStore.searchCorrelation(TraceStore.context()));
                return execution.execute(request, body);
            });
            template.setInterceptors(interceptors);
            TraceStore.withSearchContext("retrievalExecutionId", () ->
                    TraceStore.withSearchContext("searchExecutionId", () -> {
                        Map<String, Object> logicalIds = TraceStore.searchCorrelation(TraceStore.context());
                        assertEquals(2, logicalIds.size());
                        BraveSearchResult result = service.searchWithMeta("sec08 synthetic document", 3);
                        assertEquals(BraveSearchResult.Status.OK, result.status());
                        assertEquals(1, result.snippets().size());
                        assertEquals(1, wire.requests.size());
                        Map<?, ?> row = onlyBraveAttempt();
                        assertEquals(3, boundaryIds.get().size(), "attempt ID must exist inside the real client boundary");
                        boundaryIds.get().forEach((key, value) -> assertEquals(value, row.get(key)));
                        assertEquals(logicalIds, TraceStore.searchCorrelation(TraceStore.context()), "attempt scope must restore its caller");
                        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(query(wire.requests.get(0).uri(), "q")), row.get("queryHash"));
                        assertEquals(1, row.get("returnedCount"));
                        assertEquals(1, row.get("afterFilterCount"));
                        assertEquals(200, row.get("httpStatus"));
                        assertEquals("OK", row.get("outcome"));
                        assertEquals("none", row.get("failureReason"));
                        assertEquals("brave", row.get("provider"));
                        assertEquals(true, row.get("clientAttemptObserved"));
                        assertEquals("resttemplate_exchange", row.get("clientAttemptBoundary"));
                        assertEquals(false, row.get("providerReceiptObserved"));
                        assertTrue(((Number) row.get("elapsedMs")).longValue() >= 0);
                        assertTrue(((Number) row.get("finishedAtEpochMs")).longValue() >= ((Number) row.get("startedAtEpochMs")).longValue());
                        assertEquals(java.util.Set.of("retrievalExecutionId", "searchExecutionId", "providerAttemptId", "provider", "queryHash", "countScope",
                                "clientAttemptObserved", "clientAttemptBoundary", "providerReceiptObserved", "startedAtEpochMs", "finishedAtEpochMs",
                                "elapsedMs", "httpStatus", "returnedCount", "afterFilterCount", "outcome", "failureReason"), row.keySet());
                        String trace = String.valueOf(TraceStore.getAll());
                        assertFalse(trace.contains("sec08 synthetic document"));
                        assertFalse(trace.contains("fixture-brave-key"));
                        assertFalse(trace.contains(BRAVE_BODY));
                        System.out.println("SEC08_BRAVE logicalScopes=2 loopbackReceipts=1 returned=1 attemptRecordPresent=true providerReceiptObserved=false");
                        return result;
                    }));
        }
    }

    @Test void braveAttemptRowsPreserveEachHttpAndParserOutcome() throws Exception {
        record Case(int status, String body, String reason, Integer returned) { }
        for (Case scenario : List.of(new Case(200, " ", "blank-response", null), new Case(200, "{", "json-parse-error", null),
                new Case(200, "{\"web\":{\"results\":[]}}", "true_zero", 0), new Case(201, BRAVE_BODY, "none", 1),
                new Case(429, "{}", "rate-limit", null), new Case(503, "{}", "http-503", null), new Case(500, "{}", "http-error", null))) {
            TraceStore.clear();
            try (Fixture wire = new Fixture(scenario.status(), scenario.body(), NAVER_BODY, Map.of())) {
                BraveSearchResult result = brave(wire).searchWithMeta("sec08 synthetic outcome", 3);
                assertEquals(1, wire.requests.size());
                Map<?, ?> row = onlyBraveAttempt();
                assertNotNull(row.get("providerAttemptId"));
                assertEquals(scenario.status(), row.get("httpStatus"));
                assertEquals(scenario.reason(), row.get("failureReason"));
                assertEquals(result.status().name(), row.get("outcome"));
                assertEquals(scenario.returned() == null ? "unknown" : scenario.returned(), row.get("returnedCount"));
                assertEquals(false, row.get("providerReceiptObserved"));
            }
        }
    }

    @Test void bravePreWireGatesAndCacheOnlyCreateNoAttempt() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            BraveSearchService service = brave(wire);
            TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()));
            assertTrue(service.searchWithMeta("sec08 expired", 3).snippets().isEmpty());
            TimeBudgetContext.clear();
            ReflectionTestUtils.setField(service, "configEnabled", false);
            service.init();
            assertEquals(BraveSearchResult.Status.DISABLED, service.searchWithMeta("sec08 disabled", 3).status());
            var cache = new org.springframework.cache.concurrent.ConcurrentMapCacheManager("webSearchCache");
            cache.getCache("webSearchCache").put("sec08 cached-3", List.of("synthetic cached snippet"));
            ReflectionTestUtils.setField(service, "cacheManager", cache);
            assertEquals(1, service.searchCacheOnly("sec08 cached", 3).size());
            assertTrue(wire.requests.isEmpty());
            assertNull(TraceStore.get("web.brave.attempt.runs"));
            assertNull(TraceStore.get("providerAttemptId"));
        }
    }

    @Test void braveConcurrentAttemptRowsDoNotReadSharedScalarOutcomes() throws Exception {
        var parent = new java.util.concurrent.ConcurrentHashMap<String, Object>();
        var scoped = TraceStore.searchContext(TraceStore.searchContext(parent, "retrievalExecutionId"), "searchExecutionId");
        try (Fixture ok = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of());
             Fixture error = new Fixture(500, "{}", NAVER_BODY, Map.of())) {
            var workers = Executors.newFixedThreadPool(2);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<BraveSearchResult>>();
                for (Fixture wire : List.of(ok, error)) futures.add(workers.submit(() -> {
                    TraceStore.installContext(scoped);
                    try { return brave(wire).searchWithMeta(wire == ok ? "sec08 parallel ok" : "sec08 parallel error", 3); }
                    finally { TraceStore.clear(); }
                }));
                assertEquals(BraveSearchResult.Status.OK, futures.get(0).get(10, TimeUnit.SECONDS).status());
                assertEquals(BraveSearchResult.Status.HTTP_ERROR, futures.get(1).get(10, TimeUnit.SECONDS).status());
                var rows = assertInstanceOf(List.class, parent.get("web.brave.attempt.runs"));
                assertEquals(2, rows.size());
                var ids = new java.util.HashSet<Object>();
                for (Object value : rows) {
                    Map<?, ?> row = assertInstanceOf(Map.class, value);
                    assertTrue(ids.add(row.get("providerAttemptId")));
                    assertEquals(scoped.get("searchExecutionId"), row.get("searchExecutionId"));
                    boolean success = com.example.lms.trace.SafeRedactor.hashValue("sec08 parallel ok").equals(row.get("queryHash"));
                    assertEquals(success ? 200 : 500, row.get("httpStatus"));
                    assertEquals(success ? "none" : "http-error", row.get("failureReason"));
                    assertEquals(success ? 1 : "unknown", row.get("returnedCount"));
                }
                assertEquals(1, ok.requests.size());
                assertEquals(1, error.requests.size());
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void braveAttemptRowsUseExistingBoundedImmutableStorage() {
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("ordinal", 0);
        TraceStore.append("web.brave.attempt.runs", mutable);
        mutable.put("ordinal", -1);
        for (int i = 1; i < 130; i++) TraceStore.append("web.brave.attempt.runs", Map.of("ordinal", i));
        var rows = assertInstanceOf(List.class, TraceStore.get("web.brave.attempt.runs"));
        assertEquals(128, rows.size());
        assertEquals(2L, TraceStore.getLong("web.brave.attempt.runs.dropped"));
        assertEquals(2, ((Map<?, ?>) rows.get(0)).get("ordinal"));
        assertThrows(UnsupportedOperationException.class, () -> rows.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> ((Map) rows.get(0)).put("ordinal", -1));
    }

    @Test void braveClosedAttemptSinkDoesNotReplaceProviderOutcome() throws Exception {
        var parent = new java.util.concurrent.ConcurrentHashMap<String, Object>() {
            @Override public Object compute(String key, java.util.function.BiFunction<? super String, ? super Object, ?> function) {
                if ("web.brave.attempt.runs".equals(key)) throw new UnsupportedOperationException("synthetic closed attempt sink");
                return super.compute(key, function);
            }
        };
        TraceStore.installContext(parent);
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            assertEquals(BraveSearchResult.Status.OK, brave(wire).searchWithMeta("sec08 closed sink", 3).status());
            assertEquals(1, wire.requests.size());
            assertSame(parent, TraceStore.context());
        }
    }

    private static Map<?, ?> onlyBraveAttempt() {
        var rows = assertInstanceOf(List.class, TraceStore.get("web.brave.attempt.runs"));
        assertEquals(1, rows.size());
        return assertInstanceOf(Map.class, rows.get(0));
    }

    @Test void braveWireDistinguishesValidEmptyMalformedHtmlAndAuthenticationFailure() throws Exception {
        for (String body : List.of("{", "<html>synthetic login</html>", "{\"web\":{}}")) {
            try (Fixture wire = new Fixture(200, body, NAVER_BODY, Map.of())) {
                BraveSearchResult result = brave(wire).searchWithMeta("synthetic shape", 3);
                assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
                assertEquals("json-parse-error", result.message());
                assertFalse(Boolean.TRUE.equals(TraceStore.get("web.brave.providerSuccess")));
                assertEquals(1, wire.requests.size());
            }
            TraceStore.clear();
        }
        try (Fixture wire = new Fixture(200, "{\"web\":{\"results\":[]}}", NAVER_BODY, Map.of())) {
            assertEquals(BraveSearchResult.Status.OK, brave(wire).searchWithMeta("synthetic empty", 3).status());
        }
        for (int status : List.of(401, 403, 500)) {
            try (Fixture wire = new Fixture(status, "{\"error\":\"synthetic private body\"}", NAVER_BODY, Map.of())) {
                BraveSearchResult result = brave(wire).searchWithMeta("synthetic http error", 3);
                assertEquals(BraveSearchResult.Status.HTTP_ERROR, result.status());
                assertEquals(status, result.httpStatus());
                assertEquals(1, wire.requests.size(), "authentication failure must not rotate keys or retry");
                assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic private body"));
            }
        }
    }

    @Test void naverWireDistinguishesEmptyInvalidBodyAndHttpFailure() throws Exception {
        for (String body : List.of("{", "<html>synthetic login</html>", "{\"items\":{}}")) {
            try (Fixture wire = new Fixture(200, BRAVE_BODY, body, Map.of())) {
                var result = naver(wire, "fixtureid:fixturesecret").searchWithTraceSync("합성 오류", 3, Duration.ofSeconds(5));
                assertTrue(result.snippets().isEmpty());
                assertEquals("PROVIDER_ERROR", TraceStore.getString("web.naver.failureClass"));
                assertEquals(1, wire.requests.size());
            }
            TraceStore.clear();
        }
        try (Fixture wire = new Fixture(200, BRAVE_BODY, "{\"items\":[]}", Map.of())) {
            assertTrue(naver(wire, "fixtureid:fixturesecret").searchWithTraceSync("합성 없음", 3).snippets().isEmpty());
            assertEquals("TRUE_ZERO", TraceStore.getString("web.naver.failureClass"));
        }
        TraceStore.clear();
        try (Fixture wire = new Fixture(403, BRAVE_BODY, "{\"errorCode\":\"024\"}", Map.of())) {
            assertTrue(naver(wire, "fixtureid:fixturesecret").searchWithTraceSync("합성 인증", 3).snippets().isEmpty());
            assertEquals("AUTH_OR_CONFIG", TraceStore.getString("web.naver.failureClass"));
            assertEquals(1, wire.requests.size());
        }
    }

    @Test void expiredBudgetAndMissingCredentialsDoNotReachEitherWire() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            NaverSearchService disabled = naver(wire, "");
            assertFalse(disabled.isEnabled());
            assertTrue(disabled.searchSnippetsSync("합성 비활성", 3).isEmpty());
            TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()));
            assertTrue(brave(wire).searchWithMeta("synthetic expired", 3).snippets().isEmpty());
            assertTrue(naver(wire, "fixtureid:fixturesecret").searchSnippetsSync("합성 만료", 3).isEmpty());
            assertTrue(wire.requests.isEmpty());
        }
    }

    @Test void brave429UsesOnlyExhaustedResetWindowsWhenRetryAfterIsAbsent() throws Exception {
        try (Fixture wire = new Fixture(429, "{}", "{}", Map.of(
                "X-RateLimit-Remaining", "0, 1000", "X-RateLimit-Reset", "7, 1419704"))) {
            BraveSearchService service = brave(wire);
            BraveSearchResult result = service.searchWithMeta("synthetic rate limit", 3);
            assertEquals(BraveSearchResult.Status.HTTP_429, result.status());
            assertEquals(7_000L, TraceStore.getLong("web.brave.retryAfterMs"));
            assertTrue(result.cooldownMs() <= 30_000L);
            service.searchWithMeta("synthetic cooldown", 3);
            assertEquals(1, wire.requests.size());
        }
    }

    @Test void requestReadTimeoutClosesTheRealSocketAndThenReturnsItsWorker() throws Exception {
        try (var socketServer = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            socketServer.setSoTimeout(5000);
            var observer = Executors.newSingleThreadExecutor();
            var peerClosed = observer.submit(() -> {
                try (var socket = socketServer.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { /* consume only synthetic headers */ }
                    try { return socket.getInputStream().read() == -1; }
                    catch (java.net.SocketException reset) { return true; }
                }
            });
            try {
                TimeBudgetContext.set(new TimeBudget(600));
                BraveSearchResult result = brave("http://127.0.0.1:" + socketServer.getLocalPort() + "/res/v1/web/search")
                        .searchWithMeta("synthetic socket deadline", 3);
                assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
                assertEquals("timeout", result.message());
                assertTrue(peerClosed.get(3, TimeUnit.SECONDS), "peer must observe actual EOF/reset, not just Future cancellation");
                assertTrue(result.elapsedMs() < 2000);
            } finally {
                observer.shutdownNow();
                assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void braveRateHeadersKeepRetryAfterPrecedenceAndBoundInvalidOrExhaustedWindows() throws Exception {
        List<Map<String, String>> cases = List.of(
                Map.of("Retry-After", "4", "X-RateLimit-Remaining", "0, 0", "X-RateLimit-Reset", "7, 1419704"),
                Map.of("X-RateLimit-Remaining", "0, 0", "X-RateLimit-Reset", "7, 1419704"),
                Map.of("X-RateLimit-Remaining", "invalid", "X-RateLimit-Reset", "invalid"));
        long[] expected = {4_000, 30_000, 2_000};
        for (int i = 0; i < cases.size(); i++) {
            try (Fixture wire = new Fixture(429, "{}", "{}", cases.get(i))) {
                assertEquals(BraveSearchResult.Status.HTTP_429, brave(wire).searchWithMeta("synthetic quota", 3).status());
                assertEquals(expected[i], TraceStore.getLong("web.brave.retryAfterMs"));
                assertEquals(1, wire.requests.size());
            }
            TraceStore.clear();
        }
    }

    @Test void braveQpsAdmissionConsumesOnlyRemainingBudgetAndKeepsZeroAsNonblocking() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            BraveSearchService service = brave(wire.origin() + "/res/v1/web/search", 500);
            var remaining = new java.util.concurrent.atomic.AtomicLong(40);
            TimeBudgetContext.set(controlledBudget(remaining));
            var limiter = mock(com.google.common.util.concurrent.RateLimiter.class);
            when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(call -> {
                        assertEquals(40L, call.getArgument(0, Long.class));
                        remaining.set(0);
                        return false;
                    });
            ReflectionTestUtils.setField(service, "rateLimiter", limiter);
            BraveSearchResult result = service.searchWithMeta("synthetic admission", 3);
            assertEquals("request_budget_exhausted", result.message());
            assertFalse(service.isCoolingDown());
            assertTrue(wire.requests.isEmpty());
            TimeBudgetContext.set(new TimeBudget(2000));
            assertEquals(BraveSearchResult.Status.OK, brave(wire).searchWithMeta("synthetic nonblocking", 3).status());
            assertEquals(1, wire.requests.size(), "configured acquire zero remains a nonblocking try");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void naverDelayedSubscriptionDoesNotStartWireAfterTheCapturedBudgetExpires(boolean injectedExecutor) throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            var remaining = new java.util.concurrent.atomic.AtomicLong(3000);
            TimeBudgetContext.set(controlledBudget(remaining));
            RateLimitPolicy policy = mock(RateLimitPolicy.class);
            when(policy.allowedExpansions()).thenReturn(1);
            when(policy.currentDelayMs()).thenAnswer(call -> { remaining.set(0); return 1L; });
            NaverSearchService service = naver(wire, "fixtureid:fixturesecret", policy);
            if (!injectedExecutor) ReflectionTestUtils.setField(service, "llmFastExecutor", null);
            assertTrue(service.searchWithTraceSync("합성 지연", 3).snippets().isEmpty());
            assertTrue(wire.requests.isEmpty(), "delayed first subscription cannot reopen an expired request");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void naverRetryDoesNotReplenishTheOriginalBudget(boolean injectedExecutor) throws Exception {
        try (Fixture wire = new Fixture(500, BRAVE_BODY, "{}", Map.of())) {
            var remaining = new java.util.concurrent.atomic.AtomicLong(3000);
            TimeBudgetContext.set(controlledBudget(remaining));
            wire.onRequest = () -> remaining.set(0);
            NaverSearchService service = naver(wire, "fixtureid:fixturesecret");
            if (!injectedExecutor) ReflectionTestUtils.setField(service, "llmFastExecutor", null);
            ReflectionTestUtils.setField(service, "retryMaxAttempts", 1);
            ReflectionTestUtils.setField(service, "retryInitialBackoffMs", 1L);
            ReflectionTestUtils.setField(service, "retryMaxBackoffMs", 1L);
            assertTrue(service.searchWithTraceSync("합성 재시도", 3).snippets().isEmpty());
            assertEquals(1, wire.requests.size(), "a response that consumed the budget cannot schedule a second wire call");
        }
    }

    @Test void aTimedOutWaiterDoesNotAbortAnAlreadyRunningSharedNaverSocket() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            var arrived = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            wire.beforeResponse = () -> {
                arrived.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            };
            NaverSearchService service = naver(wire, "fixtureid:fixturesecret");
            var callers = Executors.newFixedThreadPool(2);
            try {
                var first = callers.submit(() -> {
                    TimeBudgetContext.set(new TimeBudget(200));
                    try { return service.searchSnippetsSync("합성 공유 문서", 3, Duration.ofMillis(150)); }
                    finally { TimeBudgetContext.clear(); TraceStore.clear(); }
                });
                assertTrue(arrived.await(3, TimeUnit.SECONDS));
                assertTrue(first.get(3, TimeUnit.SECONDS).isEmpty());
                var second = callers.submit(() -> service.searchSnippetsSync("합성 공유 문서", 3, Duration.ofSeconds(3)));
                release.countDown();
                assertEquals(List.of("- Naver fixture: synthetic document"), second.get(3, TimeUnit.SECONDS));
                assertEquals(1, wire.requests.size(), "second waiter consumes the same running cache load");
            } finally {
                release.countDown(); callers.shutdownNow();
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void connectionRefusedIsTransportFailureRatherThanTimeout() throws Exception {
        int unusedPort;
        try (var reservation = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reservation.getLocalPort();
        }
        BraveSearchResult result = brave("http://127.0.0.1:" + unusedPort + "/res/v1/web/search")
                .searchWithMeta("synthetic refused connection", 3);
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("transport-error", result.message());
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.resourceAccess"));
    }

    @Test void naverFilterZeroIsDistinctFromAValidEmptyResponseOnTheWire() throws Exception {
        try (Fixture wire = new Fixture(200, BRAVE_BODY, NAVER_BODY, Map.of())) {
            NaverSearchService service = naver(wire, "fixtureid:fixturesecret");
            ReflectionTestUtils.setField(service, "blockedDomainsCsv", "example.test");
            assertTrue(service.searchWithTraceSync("합성 필터", 3).snippets().isEmpty());
            assertEquals("FILTER_ZERO", TraceStore.getString("web.naver.failureClass"));
            assertEquals(1, wire.requests.size());
        }
    }

    private static TimeBudget controlledBudget(java.util.concurrent.atomic.AtomicLong remaining) {
        TimeBudget budget = mock(TimeBudget.class);
        when(budget.remainingMillis()).thenAnswer(call -> remaining.get());
        when(budget.expired()).thenAnswer(call -> remaining.get() <= 0);
        when(budget.capWaitMillis(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call ->
                Math.min(Math.max(0L, call.getArgument(0, Long.class)), remaining.get()));
        return budget;
    }

    private static BraveSearchService brave(Fixture wire) {
        return brave(wire.origin() + "/res/v1/web/search");
    }

    private static BraveSearchService brave(String url) {
        return brave(url, 0);
    }

    private static BraveSearchService brave(String url, long acquireMs) {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true, url, "fixture-brave-key", 1000, 2000, acquireMs, 200, 2000));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", "fixture-brave-key");
        ReflectionTestUtils.setField(service, "baseUrl", url);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        service.init();
        return service;
    }

    private static NaverSearchService naver(Fixture wire, String keys) {
        RateLimitPolicy policy = mock(RateLimitPolicy.class);
        when(policy.allowedExpansions()).thenReturn(1);
        return naver(wire, keys, policy);
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naver(Fixture wire, String keys, RateLimitPolicy policy) {
        WebClient client = WebClient.builder().filter((request, next) -> {
            URI original = request.url();
            assertEquals("https", original.getScheme());
            assertEquals("openapi.naver.com", original.getHost());
            URI local = URI.create(wire.origin() + original.getRawPath() + "?" + original.getRawQuery());
            return next.exchange(ClientRequest.from(request).url(local).build());
        }).build();
        GuardProfileProps guard = mock(GuardProfileProps.class);
        when(guard.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);
        NaverSearchService service = new NaverSearchService(mock(QueryTransformer.class),
                mock(MemoryReinforcementService.class), mock(ObjectProvider.class), mock(EmbeddingStore.class),
                mock(EmbeddingModel.class), (Supplier<Long>) () -> null, null, keys, "", "", 16, 30,
                mock(PlatformTransactionManager.class), policy, client, mock(ObjectProvider.class), null);
        ReflectionTestUtils.setField(service, "guardProfileProps", guard);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 2000L);
        ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 3000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "similarThreshold", 0.86d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        // Match the active Spring path, including its real context-aware scheduling boundary.
        ReflectionTestUtils.setField(service, "llmFastExecutor", wire.providerExecutor);
        wire.naverServices.add(service);
        return service;
    }

    private static String query(URI uri, String key) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(key)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private record Captured(String method, URI uri, com.sun.net.httpserver.Headers headers) { }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService providerExecutor =
                new com.example.lms.config.SearchExecutorConfig().llmFastExecutor();
        final List<NaverSearchService> naverServices = new CopyOnWriteArrayList<>();
        final List<Captured> requests = new CopyOnWriteArrayList<>();
        volatile Runnable onRequest = () -> { };
        volatile Runnable beforeResponse = () -> { };
        Fixture(int status, String braveBody, String naverBody, Map<String, String> headers) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders()));
                onRequest.run();
                beforeResponse.run();
                byte[] body = (exchange.getRequestURI().getPath().contains("naver")
                        || exchange.getRequestURI().getPath().contains("webkr") ? naverBody : braveBody).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(status, body.length);
                try (var response = exchange.getResponseBody()) { response.write(body); }
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() throws InterruptedException {
            server.stop(0);
            naverServices.forEach(service -> ReflectionTestUtils.invokeMethod(service, "shutdownScheduler"));
            providerExecutor.shutdownNow();
            assertTrue(providerExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
