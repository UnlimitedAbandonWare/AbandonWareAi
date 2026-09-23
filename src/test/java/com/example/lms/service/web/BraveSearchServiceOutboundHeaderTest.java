package com.example.lms.service.web;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BraveSearchServiceOutboundHeaderTest {

    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String TEST_TOKEN = "brave-outbound-header-test-token";

    @AfterEach
    void clearContext() {
        MDC.clear();
        TraceStore.clear();
        TimeBudgetContext.clear();
    }

    @Test
    void configuredTimeoutNeverExceedsCallerRemainingBudget() {
        BraveSearchService service = enabledService();
        service.applyRestTemplateTimeout(5_000L);
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(180L);
        TimeBudgetContext.set(requestBudget);
        RestTemplate sharedTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        ClientHttpRequestInterceptor interceptor = mock(ClientHttpRequestInterceptor.class);
        service.addRestTemplateInterceptorIfAbsent(interceptor);

        RestTemplate requestTemplate =
                ReflectionTestUtils.invokeMethod(service, "requestScopedTemplateForCurrentBudget");
        SimpleClientHttpRequestFactory requestFactory =
                (SimpleClientHttpRequestFactory) ReflectionTestUtils.getField(requestTemplate, "requestFactory");
        int connectTimeoutMs = (int) ReflectionTestUtils.getField(requestFactory, "connectTimeout");
        int readTimeoutMs = (int) ReflectionTestUtils.getField(requestFactory, "readTimeout");

        assertTrue(connectTimeoutMs > 0 && connectTimeoutMs <= 180);
        assertTrue(readTimeoutMs > 0 && readTimeoutMs <= 180);
        assertNotSame(sharedTemplate, requestTemplate);
        assertTrue(requestTemplate.getInterceptors().contains(interceptor));
        assertSame(sharedTemplate.getErrorHandler(), requestTemplate.getErrorHandler());
    }

    @Test
    void exhaustedCallerBudgetSkipsOutboundWithFixedReason() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(0L);
        TimeBudgetContext.set(requestBudget);

        BraveSearchResult result = service.searchWithMeta("bounded exhausted budget query", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("request_budget_exhausted", result.message());
        assertEquals("request_budget_exhausted", TraceStore.get("web.brave.failureReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.requestBudgetExhausted"));
    }

    @Test
    void budgetExpiryBetweenAdmissionAndExchangeSkipsOutboundAndReleasesQuota() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TimeBudget requestBudget = mock(TimeBudget.class);
        when(requestBudget.remainingMillis()).thenReturn(180L, 0L);
        TimeBudgetContext.set(requestBudget);
        int quotaBefore = service.monthlyRemaining().get();

        BraveSearchResult result = service.searchWithMeta("budget expires before exchange", 1);

        server.verify();
        assertEquals(quotaBefore, service.monthlyRemaining().get());
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("request_budget_exhausted", result.message());
        assertEquals("request_budget_exhausted", TraceStore.get("web.brave.failureReason"));
    }

    @Test
    void outboundRequestExcludesInternalRequestAndSessionCorrelationHeaders() {
        String requestSentinel = "internal-request-correlation-sentinel";
        String sessionSentinel = "internal-session-correlation-sentinel";
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        MDC.put(LogCorrelation.KEY_REQUEST_ID, requestSentinel);
        MDC.put(LogCorrelation.KEY_SESSION_ID, sessionSentinel);
        server.expect(request -> {
                    String rawUri = request.getURI().toString();
                    assertFalse(rawUri.contains(requestSentinel));
                    assertFalse(rawUri.contains(sessionSentinel));
                })
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertRequiredAndPrivateHeaders(
                        request.getHeaders(), requestSentinel, sessionSentinel))
                .andRespond(withSuccess(validBody(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("bounded header test query", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(1, result.snippets().size());
    }

    @Test
    void requiredProviderHeadersRemainWhenCorrelationContextIsAbsent() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(request -> assertTrue(request.getURI().toString().startsWith(BASE_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> assertRequiredAndPrivateHeaders(
                        request.getHeaders(), null, null))
                .andRespond(withSuccess(validBody(), MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("no correlation context", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(1, result.snippets().size());
    }

    private static void assertRequiredAndPrivateHeaders(
            HttpHeaders headers,
            String requestSentinel,
            String sessionSentinel) {
        assertEquals(TEST_TOKEN, headers.getFirst("X-Subscription-Token"));
        assertTrue(headers.getAccept().contains(MediaType.APPLICATION_JSON));
        assertFalse(headers.containsKey("x-request-id"));
        assertFalse(headers.containsKey("x-session-id"));

        List<String> values = headers.values().stream()
                .flatMap(List::stream)
                .toList();
        if (requestSentinel != null) {
            assertTrue(values.stream().noneMatch(value -> value.contains(requestSentinel)));
        }
        if (sessionSentinel != null) {
            assertTrue(values.stream().noneMatch(value -> value.contains(sessionSentinel)));
        }
    }

    private static BraveSearchService enabledService() {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                BASE_URL,
                TEST_TOKEN,
                0.8d,
                20,
                500L,
                200L,
                2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", TEST_TOKEN);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        service.init();
        return service;
    }

    private static String validBody() {
        return """
                {"web":{"results":[{"title":"title","description":"description","url":"https://example.com/result"}]}}
                """;
    }
}
