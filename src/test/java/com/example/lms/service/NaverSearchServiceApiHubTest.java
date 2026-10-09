package com.example.lms.service;

import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.debug.ApiFailureRecorder;
import com.example.lms.debug.ApiFailureWebClientConfiguration;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NaverSearchServiceApiHubTest {
    private static final String ITEMS = "{\"items\":[{\"title\":\"Synthetic coffee evidence\","
            + "\"link\":\"https://example.org/coffee\",\"description\":\"Synthetic coffee evidence\"}]}";
    private final List<ClientRequest> requests = new ArrayList<>();
    @TempDir Path temporary;

    @AfterEach void clearTrace() { TraceStore.clear(); }

    @Test void autoUsesApiHubWithoutOpenApiCredentialsAndRetainsEvidence() {
        var service = service("", hub("auto"), HttpStatus.OK);
        assertTrue(service.isEnabled(), "API HUB has its own complete credential pair");
        var result = service.searchWithTraceSync("커피 & tea", 3);
        assertNotNull(result);
        assertTrue(result.snippets().stream().anyMatch(s -> s.contains("https://example.org/coffee")));
        assertHub(requests.get(0));
        assertEquals("NONE", result.trace().outcomeClass);
        assertEquals(1, requests.size());
    }

    @Test void forcedOpenApiIgnoresHubCredentialsAndPreservesWireContract() {
        var service = service("synthetic-open-id:synthetic-open-secret", hub("openapi"), HttpStatus.OK);
        service.searchSnippetsMono("커피 & tea", 3).block(Duration.ofSeconds(5));
        var request = requests.get(0);
        assertEquals("openapi.naver.com", request.url().getHost());
        assertEquals("/v1/search/webkr.json", request.url().getPath());
        assertEquals("query=synthetic%20coffee&display=10&start=1", request.url().getRawQuery());
        assertEquals("synthetic-open-id", request.headers().getFirst("X-Naver-Client-Id"));
        assertEquals("synthetic-open-secret", request.headers().getFirst("X-Naver-Client-Secret"));
        assertNull(request.headers().getFirst("X-NCP-APIGW-API-KEY"));
    }

    @Test void incompleteHubPairInAutoFallsBackToOpenApi() {
        var env = new MockEnvironment().withProperty("naver.apihub.client-id", "synthetic-hub-id");
        var service = service("synthetic-open-id:synthetic-open-secret", env, HttpStatus.OK);
        assertTrue(service.isEnabled());
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertEquals("openapi.naver.com", requests.get(0).url().getHost());
    }

    @Test void forcedHubMissingPairDoesNotSendOpenApiCredentials() {
        var env = new MockEnvironment().withProperty("naver.search.provider", "apihub")
                .withProperty("naver.apihub.client-id", "synthetic-hub-id");
        var service = service("synthetic-open-id:synthetic-open-secret", env, HttpStatus.OK);
        assertFalse(service.isEnabled());
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertTrue(requests.isEmpty());
        assertEquals("missing_naver_apihub_credentials", TraceStore.get("web.naver.disabledReason"));
    }

    @Test void unresolvedHubValuesAreNotCredentials() {
        var env = hub("apihub").withProperty("naver.apihub.client-secret", "changeme");
        var service = service("", env, HttpStatus.OK);
        assertFalse(service.isEnabled());
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertTrue(requests.isEmpty());
    }

    @Test void unknownProviderModeFailsClosedWithoutOutboundCalls() {
        var service = service("synthetic-open-id:synthetic-open-secret", hub("typo"), HttpStatus.OK);
        assertFalse(service.isEnabled());
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertTrue(requests.isEmpty());
        assertEquals("invalid_naver_search_provider", TraceStore.get("web.naver.disabledReason"));
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void hubAuthRejectionIsOneAttemptAndNotTrueZero(int status) {
        var service = service("", hub("apihub"), HttpStatus.valueOf(status));
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertEquals(1, requests.size(), "auth failure must stop variants and retries");
        assertHub(requests.get(0));
        assertEquals("AUTH_OR_CONFIG", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerAttemptObserved"));
    }

    @Test void legacySyncUsesTheSameHubWireContract() {
        var service = service("", hub("apihub"), HttpStatus.OK);
        Object result = ReflectionTestUtils.invokeMethod(service, "callNaverApi", "synthetic coffee", null);
        assertNotNull(result);
        assertEquals(1, requests.size());
        assertHub(requests.get(0));
    }

    @Test void hubBaseUrlOverrideIsUsedWithoutChangingOpenApi() {
        var env = hub("auto").withProperty("naver.apihub.base-url", "http://127.0.0.1:18299");
        var service = service("", env, HttpStatus.OK);
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertFalse(requests.isEmpty(), "complete HUB credentials must produce an HTTP attempt");
        assertEquals("127.0.0.1", requests.get(0).url().getHost());
        assertEquals("/search/v1/webkr", requests.get(0).url().getPath());
    }

    @ParameterizedTest @ValueSource(strings = {"", "not a URI", "${UNRESOLVED}", "ftp://example.org"})
    void malformedHubEndpointIsConfigSkipWithoutProviderAttempt(String base) {
        var service = service("", hub("apihub").withProperty("naver.apihub.base-url", base), HttpStatus.OK);
        assertFalse(service.isEnabled(), "invalid local endpoint configuration must not admit the provider");
        service.searchSnippetsMono("synthetic coffee", 3).block(Duration.ofSeconds(5));
        assertTrue(requests.isEmpty());
        assertEquals("invalid_naver_search_endpoint", TraceStore.get("web.naver.disabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerAttemptObserved"));
    }

    @Test void uriBuilderEncodesUtf8OnceForBothModes() {
        var hubService = service("", hub("auto"), HttpStatus.OK);
        java.net.URI hubUri = ReflectionTestUtils.invokeMethod(hubService, "buildWebkrUri", "커피 & tea", 3);
        assertEquals("query=%EC%BB%A4%ED%94%BC%20%26%20tea&display=3&start=1&format=json", hubUri.getRawQuery());
        var openService = service("synthetic-open-id:synthetic-open-secret", hub("openapi"), HttpStatus.OK);
        java.net.URI openUri = ReflectionTestUtils.invokeMethod(openService, "buildWebkrUri", "커피 & tea", 3);
        assertEquals("query=%EC%BB%A4%ED%94%BC%20%26%20tea&display=3&start=1", openUri.getRawQuery());
    }

    @Test void realTerminalSuccessRecoversOneHttpIncidentWithoutDoubleCounting() {
        var recorder = recorder();
        try {
            service("", hub("apihub"), HttpStatus.UNAUTHORIZED, recorder, "{}").searchSnippetsMono("synthetic coffee", 3).block();
            assertEquals(1, recorder.snapshot().size());
            assertEquals(1, recorder.snapshot().get(0).count(), "HTTP observer and terminal must share one owner");
            service("", hub("apihub"), HttpStatus.OK, recorder, ITEMS).searchSnippetsMono("synthetic coffee", 3).block();
            var incident = recorder.snapshot().get(0);
            assertEquals(1, incident.count());
            assertEquals(0, incident.consecutive(), "a new parsed provider receipt closes the failure streak");
            assertNotNull(incident.recoveredAt());
        } finally { recorder.close(); }
    }

    @Test void honestZeroReceiptRecoversTransportButIsNotEvidence() {
        var recorder = recorder();
        try {
            service("", hub("apihub"), HttpStatus.FORBIDDEN, recorder, "{}").searchSnippetsMono("synthetic coffee", 3).block();
            var result = service("", hub("apihub"), HttpStatus.OK, recorder, "{\"items\":[]}")
                    .searchSnippetsMono("synthetic coffee", 3).block();
            assertTrue(result.isEmpty());
            assertEquals(0, recorder.snapshot().get(0).consecutive());
            assertNotNull(recorder.snapshot().get(0).recoveredAt());
        } finally { recorder.close(); }
    }

    @Test void cachedEvidenceCannotRecoverALaterProviderFailure() {
        var recorder = recorder();
        try {
            var cached = service("", hub("apihub"), HttpStatus.OK, recorder, ITEMS);
            assertFalse(cached.searchSnippetsMono("synthetic coffee", 3).block().isEmpty());
            service("", hub("apihub"), HttpStatus.UNAUTHORIZED, recorder, "{}").searchSnippetsMono("synthetic coffee", 3).block();
            int attempts = requests.size();
            assertFalse(cached.searchSnippetsCacheOnly("synthetic coffee", 3).isEmpty());
            assertEquals(attempts, requests.size());
            assertEquals(1, recorder.snapshot().get(0).consecutive());
            assertNull(recorder.snapshot().get(0).recoveredAt());
        } finally { recorder.close(); }
    }

    @Test void legacyReceiptAlsoRecoversOnlyAfterValidParsing() {
        var recorder = recorder();
        try {
            ReflectionTestUtils.invokeMethod(service("", hub("apihub"), HttpStatus.UNAUTHORIZED, recorder, "{}"),
                    "callNaverApi", "synthetic coffee", null);
            assertEquals(1, recorder.snapshot().get(0).count());
            ReflectionTestUtils.invokeMethod(service("", hub("apihub"), HttpStatus.OK, recorder, ITEMS),
                    "callNaverApi", "synthetic coffee", null);
            assertEquals(0, recorder.snapshot().get(0).consecutive());
            assertNotNull(recorder.snapshot().get(0).recoveredAt());
        } finally { recorder.close(); }
    }

    private ApiFailureRecorder recorder() {
        return new ApiFailureRecorder(mock(DebugEventStore.class), temporary.resolve("incidents.json").toString());
    }

    @Test void cancelledClientAttemptIsNeutralAndDoesNotCreateAnIncident() {
        var recorder = recorder();
        try {
            var service = service("", hub("apihub"), HttpStatus.OK, recorder, ITEMS);
            var cancelled = WebClient.builder().exchangeFunction(request -> {
                requests.add(request);
                return Mono.error(new java.util.concurrent.CancellationException("synthetic cancellation"));
            }).build();
            ReflectionTestUtils.setField(service, "web", cancelled);
            service.searchSnippetsMono("synthetic coffee", 3).block();
            assertEquals(1, requests.size());
            assertTrue(recorder.snapshot().isEmpty(), "cancellation is not a provider failure");
        } finally { recorder.close(); }
    }

    @Test void malformedTwoHundredCannotRecoverTheProvider() {
        var recorder = recorder();
        try {
            service("", hub("apihub"), HttpStatus.UNAUTHORIZED, recorder, "{}").searchSnippetsMono("synthetic coffee", 3).block();
            service("", hub("apihub"), HttpStatus.OK, recorder, "{\"private-body\":")
                    .searchSnippetsMono("synthetic coffee", 3).block();
            assertEquals(2, recorder.snapshot().size());
            assertTrue(recorder.snapshot().stream().allMatch(row -> row.recoveredAt() == null));
            assertFalse(String.valueOf(recorder.snapshot()).contains("private-body"));
        } finally { recorder.close(); }
    }

    @Test void immutableDiagnosticContextCannotDisableValidCredentials() {
        var service = service("synthetic-open-id:synthetic-open-secret", hub("openapi"), HttpStatus.OK);
        TraceStore.installContext(java.util.Map.of());
        assertTrue(service.isEnabled(), "diagnostic writes must not become a pre-wire admission gate");
    }

    private MockEnvironment hub(String mode) {
        return new MockEnvironment().withProperty("naver.search.provider", mode)
                .withProperty("naver.apihub.client-id", "synthetic-hub-id")
                .withProperty("naver.apihub.client-secret", "synthetic-hub-secret");
    }

    private void assertHub(ClientRequest request) {
        assertEquals("naverapihub.apigw.ntruss.com", request.url().getHost());
        assertEquals("/search/v1/webkr", request.url().getPath());
        assertTrue(request.url().getRawQuery().endsWith("&start=1&format=json"));
        assertEquals("synthetic-hub-id", request.headers().getFirst("X-NCP-APIGW-API-KEY-ID"));
        assertEquals("synthetic-hub-secret", request.headers().getFirst("X-NCP-APIGW-API-KEY"));
        assertNull(request.headers().getFirst("X-Naver-Client-Id"));
        assertNull(request.headers().getFirst("X-Naver-Client-Secret"));
    }

    @SuppressWarnings("unchecked")
    private NaverSearchService service(String keys, MockEnvironment env, HttpStatus status) {
        return service(keys, env, status, null, status == HttpStatus.OK ? ITEMS : "{}");
    }

    @SuppressWarnings("unchecked")
    private NaverSearchService service(String keys, MockEnvironment env, HttpStatus status,
                                       ApiFailureRecorder recorder, String body) {
        var transformer = mock(QueryTransformer.class);
        when(transformer.transform(anyString(), anyString())).thenReturn(List.of("synthetic coffee", "second coffee", "third coffee"));
        var rate = mock(RateLimitPolicy.class);
        when(rate.allowedExpansions()).thenReturn(3);
        var profile = mock(GuardProfileProps.class);
        when(profile.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json")
                    .body(body).build());
        });
        if (recorder != null) {
            org.springframework.boot.web.reactive.function.client.WebClientCustomizer customizer =
                    ReflectionTestUtils.invokeMethod(new ApiFailureWebClientConfiguration(), "apiFailureWebClientCustomizer", recorder);
            customizer.customize(builder);
        }
        var client = builder.build();
        var service = new NaverSearchService(transformer, mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class), mock(EmbeddingStore.class), mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null, null, keys, "", "", 16L, 30L,
                mock(PlatformTransactionManager.class), rate, client, mock(ObjectProvider.class), null);
        // Real Spring @Value injection: a missing implementation still runs with its current behavior.
        var factory = new DefaultListableBeanFactory();
        factory.setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
        factory.addEmbeddedValueResolver(env::resolvePlaceholders);
        var injector = new AutowiredAnnotationBeanPostProcessor();
        injector.setAutowiredAnnotationType(Value.class);
        injector.setBeanFactory(factory);
        injector.processInjection(service);
        ReflectionTestUtils.setField(service, "guardProfileProps", profile);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "enableKeywordFilter", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        if (recorder != null && org.springframework.util.ReflectionUtils.findField(NaverSearchService.class, "apiFailureRecorder") != null)
            ReflectionTestUtils.setField(service, "apiFailureRecorder", recorder);
        return service;
    }
}
