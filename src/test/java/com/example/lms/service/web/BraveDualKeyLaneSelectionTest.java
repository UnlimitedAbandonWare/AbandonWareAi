package com.example.lms.service.web;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BraveDualKeyLaneSelectionTest {

    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String FREE = "brave-free-test-token";
    private static final String BASE = "brave-base-test-token";
    private static final String RETIRED = "brave-retired-subscription-token";

    @AfterEach
    void clear() {
        TraceStore.clear();
    }

    @Test
    void freeLaneSendsFreeTokenOnSubscriptionHeader() {
        BraveSearchService service = service(FREE, BASE);
        MockRestServiceServer server = bind(service);
        server.expect(method(HttpMethod.GET))
                .andExpect(header("X-Subscription-Token", FREE))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("free lane query", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals("free", TraceStore.get("web.brave.keyLane"));
    }

    @Test
    void exhaustedFreePromotesToBaseWithoutSpendingFreeCounter() {
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(0);
        ReflectionTestUtils.setField(service, "quotaExhausted", true);
        int remaining = service.monthlyRemaining().get();
        MockRestServiceServer server = bind(service);
        server.expect(method(HttpMethod.GET))
                .andExpect(header("X-Subscription-Token", BASE))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("base after free quota", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(remaining, service.monthlyRemaining().get());
        assertEquals("base", TraceStore.get("web.brave.keyLane"));
    }

    @Test
    void lastFreeSlotStillSendsFreeToken() {
        // Boundary defect: reserving the last FREE slot sets quotaExhausted
        // before the header is written, so the re-evaluated token flipped to
        // BASE. The request that consumed the FREE slot must send the FREE key.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        MockRestServiceServer server = bind(service);
        server.expect(method(HttpMethod.GET))
                .andExpect(header("X-Subscription-Token", FREE))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("last free slot query", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertTrue(service.isQuotaExhausted());
        assertEquals("free", TraceStore.get("web.brave.keyLane"));
    }

    @Test
    void lastFreeSlotWithoutBaseStillSendsFreeToken() {
        // Same boundary with no BASE key: the re-evaluated token became blank,
        // so the wire request lost authentication for a granted FREE slot.
        BraveSearchService service = service(FREE, "");
        service.monthlyRemaining().set(1);
        MockRestServiceServer server = bind(service);
        server.expect(method(HttpMethod.GET))
                .andExpect(header("X-Subscription-Token", FREE))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("last free slot no base", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
    }

    @Test
    void concurrentReservationsNeverPairFreeGrantWithBaseToken() throws Exception {
        // Two callers racing on the last FREE slot: exactly one may hold a
        // granted reservation, and a granted reservation must always carry
        // the FREE token it was accounted under.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        int threads = 4;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<BraveSearchService.LaneReservation>> futures =
                new java.util.ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return service.reserveForRequest();
                }));
            }
            start.countDown();
            int grantedFree = 0;
            int denied = 0;
            int unmanagedBase = 0;
            for (var future : futures) {
                BraveSearchService.LaneReservation lane = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (lane.reservation().reserved()) {
                    grantedFree++;
                    assertEquals(FREE, lane.token(),
                            "a granted FREE reservation must carry the FREE token");
                    assertTrue(lane.freeLane());
                } else if (!lane.reservation().allowed()) {
                    denied++;
                } else {
                    unmanagedBase++;
                    assertEquals(BASE, lane.token());
                }
            }
            assertEquals(1, grantedFree, "exactly one caller may consume the last FREE slot");
            assertEquals(threads - 1, denied + unmanagedBase);
            assertTrue(service.monthlyRemaining().get() >= 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsWireExactlyOneFreeTokenOnLastSlot() throws Exception {
        // Request-path race on the last FREE slot. The wire header must follow
        // the lane that actually holds the reservation: exactly one request
        // may send FREE; the other follows the existing failover policy
        // (BASE header) or is locally blocked — never a null/blank token.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        ConcurrentLinkedQueue<MockClientHttpRequest> sent = recordingFactory(service, HttpStatus.OK);

        List<BraveSearchResult> results = raceTwo(service);

        List<String> tokens = sent.stream()
                .map(r -> r.getHeaders().getFirst("X-Subscription-Token"))
                .toList();
        assertEquals(1, tokens.stream().filter(FREE::equals).count(),
                "exactly one wire request may carry the FREE token");
        assertTrue(tokens.stream().allMatch(t -> FREE.equals(t) || BASE.equals(t)),
                "no wire request may carry a missing token: " + tokens);
        // Losing racer may fail over to BASE or be locally blocked (quota
        // exhaustion also starts a cooldown window — both are existing policy).
        assertTrue(results.stream().allMatch(r -> r.status() == BraveSearchResult.Status.OK
                || r.status() == BraveSearchResult.Status.RATE_LIMIT_LOCAL
                || r.status() == BraveSearchResult.Status.COOLDOWN
                || r.status() == BraveSearchResult.Status.DISABLED),
                "unexpected status: " + results.stream().map(r -> r.status()).toList());
        assertTrue(service.monthlyRemaining().get() >= 0);
    }

    @Test
    void lastFreeSlotRaceWithoutBaseNeverSendsNullToken() throws Exception {
        // With no BASE key configured, the racer that loses the FREE slot must
        // be blocked locally — the wire must never see an absent token.
        BraveSearchService service = service(FREE, "");
        service.monthlyRemaining().set(1);
        ConcurrentLinkedQueue<MockClientHttpRequest> sent = recordingFactory(service, HttpStatus.OK);

        List<BraveSearchResult> results = raceTwo(service);

        List<String> tokens = sent.stream()
                .map(r -> r.getHeaders().getFirst("X-Subscription-Token"))
                .toList();
        assertEquals(1, sent.size(), "the losing request must be blocked before the wire");
        assertEquals(FREE, tokens.get(0));
        assertEquals(1, results.stream().filter(r -> r.status() == BraveSearchResult.Status.OK).count());
        assertEquals(1, results.stream().filter(r -> r.status() != BraveSearchResult.Status.OK).count());
    }

    @Test
    void failedHttpResponseRefundsReservedFreeSlot() {
        // A failed wire call must return the FREE slot: the next request still
        // runs on the FREE lane instead of a phantom exhaustion.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        AtomicReference<HttpStatus> status = new AtomicReference<>(HttpStatus.INTERNAL_SERVER_ERROR);
        recordingFactory(service, status);

        BraveSearchResult failed = service.searchWithMeta("fails with http 500", 1);

        assertEquals(BraveSearchResult.Status.HTTP_ERROR, failed.status());
        assertEquals(1, service.monthlyRemaining().get(), "failed request must refund the FREE slot");
        assertFalse(service.isQuotaExhausted(), "refund must clear the exhausted flag");

        // A refunded slot must be reservable again on the FREE lane.
        BraveSearchService.QuotaReservation refund = service.tryReserveFreeTierQuota();
        assertNotNull(refund, "refunded slot must be reservable again");
        service.completeFreeTierQuota(refund, new HttpHeaders(), true);
        assertEquals(0, service.monthlyRemaining().get());
    }

    @Test
    void transportFailureReleasesReservedFreeSlot() {
        // A connection-level failure happens before the provider can process
        // the request: the reserved FREE slot must be released, not leaked.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory((uri, httpMethod) -> {
            throw new ResourceAccessException("synthetic connection refused");
        });

        BraveSearchResult result = service.searchWithMeta("transport failure query", 1);

        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(1, service.monthlyRemaining().get(), "transport failure must release the FREE slot");
        assertFalse(service.isQuotaExhausted());
    }

    @Test
    void timeoutKeepsReservedSlotAsAmbiguousProviderOutcome() {
        // A client-side timeout cannot prove the provider ignored the request
        // (the request may have been processed). Keep the slot consumed
        // conservatively; provider headers or the month rollover reconcile it.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory((uri, httpMethod) -> {
            throw new ResourceAccessException("synthetic read timeout");
        });

        BraveSearchResult result = service.searchWithMeta("ambiguous timeout", 1);

        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(0, service.monthlyRemaining().get(),
                "an ambiguous timeout must keep the slot, not refund it");
        assertTrue(service.isQuotaExhausted());
    }

    @Test
    void providerReportedExhaustionSurvivesLateReservationRelease() {
        // Provider-evidence exhaustion (X-RateLimit-Remaining: 0 observed on a
        // response) is a latch, not local accounting: a late slot release from
        // a still-open request must not unlatch the provider block.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(2);

        BraveSearchService.QuotaReservation pending = service.tryReserveFreeTierQuota();
        assertTrue(pending.reserved());
        service.reconcileProviderRemaining(0);
        assertTrue(service.isQuotaExhausted());

        service.releaseFreeTierQuota(pending);

        assertEquals(1, service.monthlyRemaining().get(),
                "the late release still refunds the local slot");
        assertTrue(service.isQuotaExhausted(),
                "provider-reported exhaustion must survive a late reservation release");
        assertFalse(service.tryReserveFreeTierQuota().reserved(),
                "FREE lane must stay blocked while provider evidence says exhausted");
    }

    @Test
    void sameReservationSettlesCleanupOnlyOnce() {
        // Overlapping exception/cancel/finalize handling must settle a
        // reservation exactly once — a second refund would resurrect a slot
        // already consumed by a different request.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(2);
        BraveSearchService.QuotaReservation a = service.tryReserveFreeTierQuota();
        BraveSearchService.QuotaReservation b = service.tryReserveFreeTierQuota();
        assertTrue(a.reserved() && b.reserved());
        assertEquals(0, service.monthlyRemaining().get());

        service.releaseFreeTierQuota(a);
        service.releaseFreeTierQuota(a);
        service.completeFreeTierQuota(a, new HttpHeaders(), false);

        assertEquals(1, service.monthlyRemaining().get(),
                "a reservation must settle exactly once; the other slot must not be resurrected");
    }

    @Test
    void errorResponseWithProviderZeroHeaderConsumesReservation() {
        // An error response carrying X-RateLimit-Remaining: 0 is provider
        // evidence of exhaustion — the reservation is consumed, not refunded.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        BraveSearchService.QuotaReservation r = service.tryReserveFreeTierQuota();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");

        service.completeFreeTierQuota(r, headers, false);

        assertEquals(0, service.monthlyRemaining().get());
        assertTrue(service.isQuotaExhausted());
        assertFalse(service.tryReserveFreeTierQuota().reserved());
    }

    @Test
    void okResponseWithUnparseableBodyStillCountsConsumed() {
        // HTTP 200 means Brave processed the request (billable): a local parse
        // failure afterwards does not refund the consumed slot.
        BraveSearchService service = service(FREE, BASE);
        service.monthlyRemaining().set(1);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory((uri, httpMethod) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            request.setResponse(new MockClientHttpResponse(
                    "{not-json".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
            return request;
        });

        BraveSearchResult result = service.searchWithMeta("unparseable body", 1);

        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(0, service.monthlyRemaining().get(),
                "HTTP 200 is provider-processed; local parse failure keeps the consumed slot");
    }

    private static List<BraveSearchResult> raceTwo(BraveSearchService service) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BraveSearchResult> a = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.searchWithMeta("race query a", 1);
            });
            Future<BraveSearchResult> b = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.searchWithMeta("race query b", 1);
            });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static ConcurrentLinkedQueue<MockClientHttpRequest> recordingFactory(
            BraveSearchService service, HttpStatus status) {
        AtomicReference<HttpStatus> ref = new AtomicReference<>(status);
        return recordingFactory(service, ref);
    }

    private static ConcurrentLinkedQueue<MockClientHttpRequest> recordingFactory(
            BraveSearchService service, AtomicReference<HttpStatus> status) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        ConcurrentLinkedQueue<MockClientHttpRequest> sent = new ConcurrentLinkedQueue<>();
        restTemplate.setRequestFactory((uri, httpMethod) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            request.setResponse(new MockClientHttpResponse(
                    body().getBytes(StandardCharsets.UTF_8), status.get()));
            sent.add(request);
            return request;
        });
        return sent;
    }

    @Test
    void retiredSubscriptionTokenAloneDoesNotAuthenticate() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("BRAVE_SUBSCRIPTION_TOKEN", RETIRED);
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(env);
        BraveSearchService service = service("", "");
        ReflectionTestUtils.setField(service, "credentialResolver", resolver);
        service.init();

        assertFalse(service.isEnabled());
        assertEquals("missing_brave_api_key", service.disabledReason());
        assertTrue(ConfigValueGuards.isMissing(resolver.resolve(ProviderCredentialResolver.Provider.BRAVE).valueOrNull()));
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        BraveSearchResult result = service.searchWithMeta("retired only", 1);
        server.verify();
        assertEquals(BraveSearchResult.Status.DISABLED, result.status());
    }

    @Test
    void resolverIgnoresRetiredTokenWhenBaseKeyPresent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("BRAVE_API_KEY", BASE)
                .withProperty("BRAVE_SUBSCRIPTION_TOKEN", RETIRED);
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(env);
        assertEquals(BASE, resolver.resolve(ProviderCredentialResolver.Provider.BRAVE).valueOrNull());
        assertTrue(ConfigValueGuards.isMissing(resolver.resolveBraveFree().valueOrNull()));
    }

    private static BraveSearchService service(String free, String base) {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true, BASE_URL, base, 0.8d, 20, 500L, 200L, 2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", base);
        ReflectionTestUtils.setField(service, "apiKeyFree", free);
        ReflectionTestUtils.setField(service, "freeLaneConfigured", free != null && !free.isBlank());
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        service.init();
        return service;
    }

    private static MockRestServiceServer bind(BraveSearchService service) {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        return MockRestServiceServer.bindTo(restTemplate).build();
    }

    private static String body() {
        return """
                {"web":{"results":[{"title":"title","description":"description","url":"https://example.com/result"}]}}
                """;
    }
}
