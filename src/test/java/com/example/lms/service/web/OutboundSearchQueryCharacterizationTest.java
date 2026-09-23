package com.example.lms.service.web;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.PromptMasker;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class OutboundSearchQueryCharacterizationTest {
    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String PROVIDER_FIXTURE_TOKEN = "brave-outbound-header-test-token";
    private static final byte[] RESPONSE = ("{\"web\":{\"results\":[{\"title\":\"fixture title\","
            + "\"description\":\"harmless fixture description\",\"url\":\"https://example.test/reference\"}]}}")
            .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    @AfterEach
    void clearContext() {
        TraceStore.clear();
        TimeBudgetContext.clear();
        GuardContextHolder.clear();
        MDC.clear();
    }

    static Stream<Arguments> requestCases() {
        return Stream.of(false, true).flatMap(bounded ->
                Stream.of(false, true).flatMap(traced ->
                        Stream.of("vendor-shaped", "labelled-password")
                                .map(kind -> Arguments.of(bounded, traced, kind))));
    }

    static Stream<Arguments> blockedCases() {
        return Stream.of(false, true).flatMap(bounded ->
                Stream.of(false, true).map(traced -> Arguments.of(bounded, traced)));
    }

    @ParameterizedTest(name = "bounded={0} traced={1} category={2}")
    @MethodSource("requestCases")
    void characterizesCurrentCredentialShapedQueryAtRequestBoundary(boolean bounded, boolean traced, String kind) {
        String value = "vendor-shaped".equals(kind) ? "sk" + "-" + "fixturecredential".repeat(2)
                : "fixturepassword".repeat(2);
        String query = "Ada Lovelace compiler archive " + ("vendor-shaped".equals(kind) ? value : "password=" + value);
        Capture capture = new Capture();
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = provider(capture, naver, bounded);

        List<String> snippets = search(provider, query, traced);

        assertEquals(1, capture.requests.get());
        assertEquals(1, snippets.size());
        assertTrue(query.equals(capture.query.get()), "current-query-preserved");
        assertTrue(capture.query.get().contains(value), "credential-shaped-value-present-in-recorded-query");
        assertFalse(String.valueOf(TraceStore.getAll()).contains(value), "trace-excludes-fixture-value");
        if (bounded) {
            verifyNoInteractions(naver);
        } else {
            verify(naver).isEnabled();
            verifyNoMoreInteractions(naver);
        }
    }

    @ParameterizedTest(name = "identity category={0}")
    @ValueSource(strings = {"email", "phone"})
    void characterizesIdentityCategoryAtActualHybridBraveRequestBoundary(String category) {
        String value = category.equals("email") ? "identity.fixture@example.test" : "010-2345-6789";
        String query = "synthetic contact " + value;
        Capture capture = new Capture();
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = provider(capture, naver, true);

        assertEquals(1, provider.search(query, 1).size());
        assertEquals(1, capture.requests.get());
        assertTrue(query.equals(capture.query.get()), "identity-category-preserved-at-request-boundary");
        assertFalse(String.valueOf(TraceStore.getAll()).contains(value), "trace-excludes-identity-fixture");
        verifyNoInteractions(naver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada Lovelace Berlin compiler archive", "apiKeyFactory clientSecretService docs",
            "Bearer token authentication documentation"})
    void preservesOrdinaryQueriesAndCharacterizesLoggingMaskerMismatch(String query) {
        Capture capture = new Capture();
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = provider(capture, naver, true);

        assertEquals(1, provider.search(query, 1).size());
        assertEquals(1, capture.requests.get());
        assertTrue(query.equals(capture.query.get()), "ordinary-query-exact");
        if (query.startsWith("Bearer ")) {
            assertFalse(query.equals(PromptMasker.mask(query)), "logging-masker-changes-technical-phrase");
        } else {
            assertTrue(query.equals(PromptMasker.mask(query)), "logging-masker-preserves-ordinary-control");
        }
        verifyNoInteractions(naver);
    }

    @ParameterizedTest(name = "bounded={0} traced={1}")
    @MethodSource("blockedCases")
    void existingPrivacyBlockPreventsAnyRecordedRequest(boolean bounded, boolean traced) {
        Capture capture = new Capture();
        NaverSearchService naver = mock(NaverSearchService.class);
        HybridWebSearchProvider provider = provider(capture, naver, bounded);
        ReflectionTestUtils.setField(provider, "blockWebSearch", true);

        assertTrue(search(provider, "compiler archive password=" + "fixturepassword".repeat(2), traced).isEmpty());
        assertEquals(0, capture.requests.get());
        assertEquals(Boolean.TRUE, TraceStore.get("privacy.web.blocked"));
        verifyNoInteractions(naver);
    }

    private static List<String> search(HybridWebSearchProvider provider, String query, boolean traced) {
        return traced ? provider.searchWithTrace(query, 1).snippets() : provider.search(query, 1);
    }

    private static HybridWebSearchProvider provider(Capture capture, NaverSearchService naver, boolean bounded) {
        BraveSearchService brave = new BraveSearchService(new BraveSearchProperties(
                true, BASE_URL, PROVIDER_FIXTURE_TOKEN, 0.8d, 20, 500L, 200L, 2000L));
        ReflectionTestUtils.setField(brave, "configEnabled", true);
        ReflectionTestUtils.setField(brave, "apiKey", PROVIDER_FIXTURE_TOKEN);
        ReflectionTestUtils.setField(brave, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(brave, "timeoutMs", 2000);
        ReflectionTestUtils.setField(brave, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(brave, "adaptiveSearchEnabled", false);
        brave.init();
        assertTrue(brave.addRestTemplateInterceptorIfAbsent((request, body, execution) -> {
            capture.requests.incrementAndGet();
            String encoded = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("q");
            capture.query.set(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
            assertTrue(PROVIDER_FIXTURE_TOKEN.equals(request.getHeaders().getFirst("X-Subscription-Token")),
                    "configured-provider-header-preserved");
            // This interceptor is copied to budget-scoped templates and never delegates to network transport.
            MockClientHttpResponse response = new MockClientHttpResponse(RESPONSE, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        }));
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "primary", "BRAVE");
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", bounded);
        return provider;
    }

    private static final class Capture {
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<String> query = new AtomicReference<>();
    }
}
