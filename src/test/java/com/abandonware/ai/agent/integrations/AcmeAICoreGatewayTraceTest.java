package com.abandonware.ai.agent.integrations;

import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import com.acme.aicore.domain.model.RerankParams;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.RankingPort;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcmeAICoreGatewayTraceTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void providerFailureKeepsFanoutFailSoftAndRecordsRedactedBreadcrumb() {
        String unsafeProviderId = "private-provider fake-sensitive-token";
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(
                failingProvider(unsafeProviderId),
                okProvider()), rankingPort());

        List<Map<String, Object>> out = gateway.searchAndRank("query", 3, "ko");

        assertEquals(1, out.size());
        assertEquals("ok-1", out.get(0).get("id"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.providerFailure"));
        assertEquals("provider.search", TraceStore.get("agent.acmeGateway.providerFailure.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.acmeGateway.providerFailure.errorClass"));
        assertEquals(1L, TraceStore.get("agent.acmeGateway.providerFailure.count"));
        assertEquals(unsafeProviderId.length(), TraceStore.get("agent.acmeGateway.providerFailure.providerIdLength"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private-provider"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    @Test
    void rankingFailureReturnsEmptyWithRedactedBreadcrumb() {
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(okProvider()), failingRankingPort());

        List<Map<String, Object>> out = gateway.searchAndRank("private ranking query", 3, "ko");

        assertEquals(List.of(), out);
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.rankingFailure"));
        assertEquals("ranking.fuseAndRank", TraceStore.get("agent.acmeGateway.rankingFailure.stage"));
        assertEquals("IllegalStateException", TraceStore.get("agent.acmeGateway.rankingFailure.errorClass"));
        assertEquals(1L, TraceStore.get("agent.acmeGateway.rankingFailure.count"));
        assertEquals("ranking-failure", TraceStore.get("agent.acmeGateway.result.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.result.probeOnly"));
        assertEquals(Boolean.FALSE, TraceStore.get("agent.acmeGateway.result.verificationGatePassed"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private ranking query"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    @Test
    void providerAttemptsEmitOneOrderedRedactedTerminalRowPerObservableOutcome() {
        AtomicInteger fakeInvocations = new AtomicInteger();
        String rawQuery = "private query Authorization=Bearer fake-sensitive-token";
        SearchBundle successBundle = new SearchBundle("web", List.of(
                new SearchBundle.Doc(
                        "private-doc-id",
                        "private title",
                        "private snippet",
                        "https://private.example.test/raw",
                        "2026-08-15")));
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(
                provider("private-provider-success", fakeInvocations, Mono.just(successBundle)),
                provider("private-provider-generic", fakeInvocations,
                        Mono.error(new IllegalStateException("private generic exception"))),
                provider("private-provider-timeout", fakeInvocations,
                        Mono.error(new RuntimeException(new TimeoutException("private timeout")))),
                provider("private-provider-rate", fakeInvocations,
                        Mono.error(new RateLimitSignalException("private 429 body"))),
                provider("private-provider-nonresponse", fakeInvocations, Mono.empty()),
                provider("private-provider-empty", fakeInvocations, Mono.just(SearchBundle.empty()))),
                rankingPortFor("private-doc-id"));

        List<Map<String, Object>> out = gateway.searchAndRank(rawQuery, 3, "ko");

        assertEquals(1, out.size());
        assertEquals(6, fakeInvocations.get());
        List<Map<String, Object>> rows = providerAttemptRows();
        assertEquals(6, rows.size());
        assertEquals(
                List.of("success", "failure", "timeout", "rate-limit", "nonresponse", "zero-result"),
                rows.stream().map(row -> row.get("outcome")).toList());
        assertEquals(
                List.of("none", "exception", "timeout", "rate-limit", "nonresponse", "zero-result"),
                rows.stream().map(row -> row.get("reason")).toList());
        assertEquals(
                List.of(true, false, false, false, false, true),
                rows.stream().map(row -> row.get("responseObserved")).toList());
        Set<String> allowedFields = Set.of(
                "requestHash", "optionsHash", "providerIdHash", "providerIdLength",
                "attemptOrdinal", "sequence", "outcome", "reason", "elapsedMs", "returnedCount",
                "providerAttemptObserved", "responseObserved", "probeOnly", "verificationGatePassed");
        long priorSequence = 0L;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            assertEquals(allowedFields, row.keySet());
            assertEquals(index + 1, row.get("attemptOrdinal"));
            long sequence = ((Number) row.get("sequence")).longValue();
            assertTrue(sequence > priorSequence, row.toString());
            priorSequence = sequence;
            assertTrue(String.valueOf(row.get("requestHash")).startsWith("hash:"), row.toString());
            assertTrue(String.valueOf(row.get("optionsHash")).startsWith("hash:"), row.toString());
            assertTrue(String.valueOf(row.get("providerIdHash")).startsWith("hash:"), row.toString());
            assertTrue(((Number) row.get("providerIdLength")).intValue() > 0, row.toString());
            long elapsedMs = ((Number) row.get("elapsedMs")).longValue();
            assertTrue(elapsedMs >= 0L && elapsedMs <= 3_600_000L, row.toString());
            assertTrue(((Number) row.get("returnedCount")).intValue() >= 0, row.toString());
            assertEquals(Boolean.TRUE, row.get("providerAttemptObserved"));
            assertEquals(Boolean.TRUE, row.get("probeOnly"));
            assertEquals(Boolean.FALSE, row.get("verificationGatePassed"));
        }
        String rendered = String.valueOf(TraceStore.getAll());
        for (String raw : List.of(
                rawQuery,
                "private-provider",
                "private-doc-id",
                "private title",
                "private snippet",
                "private.example.test",
                "private generic exception",
                "private timeout",
                "private 429 body")) {
            assertFalse(rendered.contains(raw), rendered);
        }
    }

    @Test
    void failedAndNonresponseAttemptsRemainOrderedWhenFallbackSucceeds() {
        AtomicInteger fakeInvocations = new AtomicInteger();
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(
                provider("failed-first", fakeInvocations, Mono.error(new IllegalArgumentException("private"))),
                provider("no-response-second", fakeInvocations, Mono.empty()),
                provider("successful-third", fakeInvocations, Mono.just(new SearchBundle("web", List.of(
                        new SearchBundle.Doc("fallback-1", "Title", "Snippet", "https://example.test", "2026-08-15")))))),
                rankingPortFor("fallback-1"));

        List<Map<String, Object>> out = gateway.searchAndRank("fallback query", 3, "ko");

        assertEquals(1, out.size());
        assertEquals("fallback-1", out.get(0).get("id"));
        assertEquals(3, fakeInvocations.get());
        List<Map<String, Object>> rows = providerAttemptRows();
        assertEquals(List.of("failure", "nonresponse", "success"),
                rows.stream().map(row -> row.get("outcome")).toList());
        assertEquals(List.of(1, 2, 3),
                rows.stream().map(row -> row.get("attemptOrdinal")).toList());
        assertEquals(1L, TraceStore.get("agent.acmeGateway.providerFailure.count"));
    }

    @Test
    void providerIdentityFailureWhileTracingAnErrorDoesNotBreakFallback() {
        AtomicInteger fakeInvocations = new AtomicInteger();
        WebSearchProvider identityFailingProvider = new WebSearchProvider() {
            @Override
            public String id() {
                throw new IllegalStateException("private identity fake-sensitive-token");
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                fakeInvocations.incrementAndGet();
                return Mono.error(new IllegalArgumentException("private search fake-sensitive-token"));
            }
        };
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(
                identityFailingProvider,
                provider("successful-fallback", fakeInvocations, Mono.just(new SearchBundle("web", List.of(
                        new SearchBundle.Doc("fallback-identity-1", "Title", "Snippet",
                                "https://example.test", "2026-08-15")))))),
                rankingPortFor("fallback-identity-1"));

        List<Map<String, Object>> out = gateway.searchAndRank("fallback query", 3, "ko");

        assertEquals(1, out.size());
        assertEquals("fallback-identity-1", out.get(0).get("id"));
        assertEquals(2, fakeInvocations.get());
        List<Map<String, Object>> rows = providerAttemptRows();
        assertEquals(List.of("failure", "success"),
                rows.stream().map(row -> row.get("outcome")).toList());
        assertEquals(0, rows.get(0).get("providerIdLength"));
        assertTrue(String.valueOf(rows.get(0).get("providerIdHash")).startsWith("hash:"));
        assertEquals(1L, TraceStore.get("agent.acmeGateway.providerFailure.count"));
        String rendered = String.valueOf(TraceStore.getAll());
        assertFalse(rendered.contains("private identity"), rendered);
        assertFalse(rendered.contains("private search"), rendered);
        assertFalse(rendered.contains("fake-sensitive-token"), rendered);
    }

    @Test
    void emptyBundleDoesNotInferASwallowedLowerLayerTimeout() {
        AtomicInteger fakeInvocations = new AtomicInteger();
        WebSearchProvider swallowedTimeout = new WebSearchProvider() {
            @Override
            public String id() {
                return "swallowed-timeout-provider";
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                fakeInvocations.incrementAndGet();
                TraceStore.put("web.fake.failureReason", "timeout");
                return Mono.just(SearchBundle.empty());
            }
        };
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(List.of(swallowedTimeout), rankingPort());

        assertEquals(List.of(), gateway.searchAndRank("q", 3, "ko"));

        assertEquals(1, fakeInvocations.get());
        List<Map<String, Object>> rows = providerAttemptRows();
        assertEquals(1, rows.size());
        assertEquals("zero-result", rows.get(0).get("outcome"));
        assertEquals("zero-result", rows.get(0).get("reason"));
    }

    @Test
    void rankingIdsMissingFromSourceDocumentsReportAfterFilterStarvation() {
        AtomicInteger fakeInvocations = new AtomicInteger();
        SearchBundle source = new SearchBundle("web", List.of(
                new SearchBundle.Doc("source-1", "Title", "Snippet", "https://example.test", "2026-08-15")));
        AcmeAICoreGateway gateway = new AcmeAICoreGateway(
                List.of(provider("source-provider", fakeInvocations, Mono.just(source))),
                rankingPortFor("missing-ranked-id"));

        List<Map<String, Object>> out = gateway.searchAndRank("q", 3, "ko");

        assertEquals(List.of(), out);
        assertEquals(1, fakeInvocations.get());
        assertEquals("after-filter-starvation", TraceStore.get("agent.acmeGateway.result.reason"));
        assertEquals(0, TraceStore.get("agent.acmeGateway.result.returnedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.acmeGateway.result.probeOnly"));
        assertEquals(Boolean.FALSE, TraceStore.get("agent.acmeGateway.result.verificationGatePassed"));
    }

    private static WebSearchProvider failingProvider(String id) {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                return Mono.error(new IllegalStateException("secret query fake-sensitive-token"));
            }
        };
    }

    private static WebSearchProvider okProvider() {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return "ok";
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                return Mono.just(new SearchBundle("web", List.of(
                        new SearchBundle.Doc("ok-1", "Title", "Snippet", "https://example.test", "2026-06-14"))));
            }
        };
    }

    private static WebSearchProvider provider(String id, AtomicInteger invocations, Mono<SearchBundle> result) {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Mono<SearchBundle> search(WebSearchQuery query) {
                invocations.incrementAndGet();
                return result;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providerAttemptRows() {
        Object value = TraceStore.get("agent.acmeGateway.providerAttempts");
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(out);
    }

    private static RankingPort rankingPort() {
        return new RankingPort() {
            @Override
            public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
                return Mono.just(List.of(RankedDoc.of("ok-1", 0.9d)));
            }

            @Override
            public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
                return Mono.just(topN);
            }
        };
    }

    private static RankingPort rankingPortFor(String rankedId) {
        return new RankingPort() {
            @Override
            public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
                return Mono.just(List.of(RankedDoc.of(rankedId, 0.9d)));
            }

            @Override
            public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
                return Mono.just(topN);
            }
        };
    }

    private static RankingPort failingRankingPort() {
        return new RankingPort() {
            @Override
            public Mono<List<RankedDoc>> fuseAndRank(List<SearchBundle> bundles, RankingParams params) {
                return Mono.error(new IllegalStateException("private ranking fake-sensitive-token"));
            }

            @Override
            public Mono<List<RankedDoc>> rerank(List<RankedDoc> topN, RerankParams params) {
                return Mono.just(topN);
            }
        };
    }

    private static final class RateLimitSignalException extends RuntimeException {
        private RateLimitSignalException(String message) {
            super(message);
        }
    }
}
