package com.example.lms.provider;

import com.acme.aicore.adapters.search.BraveSearchProvider;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.gptsearch.web.impl.SerpApiProvider;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.guard.KeyResolver;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.infra.upstash.UpstashBackedWebCache;
import com.example.lms.infra.upstash.UpstashRateLimiter;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.TavilyWebSearchRetriever;
import com.example.lms.service.web.BraveSearchProperties;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderCredentialConsumerContractTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void braveServiceUsesApprovedMainDespiteRetiredAlias() {
        String first = "brave-provider-secret-a";
        String second = "brave-provider-secret-b";
        ProviderCredentialResolver resolver = resolver(
                "gpt-search.brave.subscription-token", first,
                "BRAVE_API_KEY", second);
        BraveSearchService service = braveService(resolver, "legacy-direct-brave-value");

        assertTrue(service.isEnabled());
        assertEquals("", service.disabledReason());
        assertEquals(second, ReflectionTestUtils.getField(service, "apiKey"));
        assertSecretsAbsent(first, second);
    }

    @Test
    void braveServiceAcceptsEqualAliasValues() {
        String shared = "brave-provider-shared-secret";
        ProviderCredentialResolver resolver = resolver(
                "gpt-search.brave.subscription-token", shared,
                "BRAVE_API_KEY", shared);
        BraveSearchService service = braveService(resolver, "legacy-direct-brave-value");

        assertTrue(service.isEnabled());
        assertEquals("", service.disabledReason());
    }

    @Test
    void reactiveBraveProviderDoesNotTouchCacheOrWireForRetiredTokenOnly() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient.Builder http = WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            throw new AssertionError("conflicting Brave aliases must not call the provider");
        });
        UpstashBackedWebCache cache = mock(UpstashBackedWebCache.class);
        when(cache.get(anyString())).thenReturn(Mono.just(Optional.empty()));
        when(cache.put(anyString(), anyString(), nullable(Duration.class))).thenReturn(Mono.empty());
        UpstashRateLimiter limiter = mock(UpstashRateLimiter.class);
        when(limiter.allow(anyString(), anyLong(), any(Duration.class))).thenReturn(Mono.just(true));
        BraveSearchProvider provider = new BraveSearchProvider(http, cache, limiter);
        ReflectionTestUtils.setField(provider, "apiKey", "legacy-direct-brave-value");
        ReflectionTestUtils.setField(provider, "enabled", true);
        ReflectionTestUtils.setField(provider, "count", 3);
        ReflectionTestUtils.setField(provider, "credentialResolver", resolver(
                "gpt-search.brave.subscription-token", "reactive-brave-secret-a",
                "BRAVE_SUBSCRIPTION_TOKEN", "reactive-brave-secret-b"));

        SearchBundle result = provider.search(new WebSearchQuery("conflicting reactive brave"))
                .block(Duration.ofSeconds(1));

        assertNotNull(result);
        assertTrue(result.docs().isEmpty());
        assertEquals(0, attempts.get());
        verify(cache, never()).get(anyString());
        verify(limiter, never()).allow(anyString(), anyLong(), any(Duration.class));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReason"));
        assertSecretsAbsent("reactive-brave-secret-a", "reactive-brave-secret-b");
    }

    @Test
    void tavilyDoesNotBuildWebClientWhenAliasesConflict() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient.Builder http = WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            throw new AssertionError("conflicting Tavily aliases must not call the provider");
        });
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(http);
        ReflectionTestUtils.setField(retriever, "apiKey", "legacy-direct-tavily-value");
        ReflectionTestUtils.setField(retriever, "maxResults", 3);
        ReflectionTestUtils.setField(retriever, "credentialResolver", resolver(
                "tavily.api.key", "tavily-provider-secret-a",
                "TAVILY_API_KEY", "tavily-provider-secret-b"));

        var result = retriever.retrieve(new Query("conflicting tavily consumer"));

        assertTrue(result.isEmpty());
        assertEquals(0, attempts.get());
        assertEquals("conflicting-credential-aliases", TraceStore.get("web.tavily.disabledReason"));
        assertSecretsAbsent("tavily-provider-secret-a", "tavily-provider-secret-b");
    }

    @Test
    void serpApiDisablesConflictingAliasesBeforeSearch() {
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "apiKey", "legacy-direct-serpapi-value");
        ReflectionTestUtils.setField(provider, "configEnabled", true);
        ReflectionTestUtils.setField(provider, "credentialResolver", resolver(
                "gpt-search.serpapi.api-key", "serpapi-provider-secret-a",
                "SERPAPI_API_KEY", "serpapi-provider-secret-b"));
        ReflectionTestUtils.invokeMethod(provider, "init");

        WebSearchResult result = provider.search(new com.example.lms.gptsearch.web.dto.WebSearchQuery(
                "conflicting serpapi consumer", 3, List.of(ProviderId.SERPAPI), null));

        assertFalse(provider.isEnabled());
        assertEquals("conflicting-credential-aliases", provider.disabledReason());
        assertTrue(result.getDocuments().isEmpty());
        assertSecretsAbsent("serpapi-provider-secret-a", "serpapi-provider-secret-b");
    }

    @Test
    void naverConflictCannotFallThroughToConstructorBridge() {
        String first = "naver-id-a:naver-secret-a";
        String second = "naver-id-b:naver-secret-b";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("naver.keys", first)
                .withProperty("NAVER_KEYS", second);
        KeyResolver keyResolver = new KeyResolver(environment);
        @SuppressWarnings("unchecked")
        ObjectProvider<KeyResolver> keyResolverProvider = mock(ObjectProvider.class);
        when(keyResolverProvider.getIfAvailable()).thenReturn(keyResolver);
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet();
            throw new AssertionError("conflicting Naver aliases must not call the provider");
        }).build();
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.transform(anyString(), anyString()))
                .thenThrow(new AssertionError("conflicting Naver aliases must not rewrite the query"));
        NaverSearchService service = naverService(webClient, transformer, first, keyResolverProvider);

        List<String> result = service.searchSnippetsMono("conflicting naver consumer", 3)
                .block(Duration.ofSeconds(5));

        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertEquals(0, attempts.get());
        verify(transformer, never()).transform(anyString(), anyString());
        assertEquals("conflicting-credential-aliases", TraceStore.get("naver.cred.disabledReason"));
        assertSecretsAbsent(first, second);
    }

    private static BraveSearchService braveService(
            ProviderCredentialResolver resolver,
            String legacyDirectValue) {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                "https://api.search.brave.com/res/v1/web/search",
                legacyDirectValue,
                1.0d,
                0,
                500L,
                200L,
                2000L));
        ReflectionTestUtils.setField(service, "credentialResolver", resolver);
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", legacyDirectValue);
        ReflectionTestUtils.invokeMethod(service, "init");
        return service;
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService naverService(
            WebClient webClient,
            QueryTransformer transformer,
            String naverKeys,
            ObjectProvider<KeyResolver> keyResolverProvider) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        when(ratePolicy.allowedExpansions()).thenReturn(3);
        when(ratePolicy.currentDelayMs()).thenReturn(0L);
        GuardProfileProps guardProfileProps = mock(GuardProfileProps.class);
        when(guardProfileProps.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);

        NaverSearchService service = new NaverSearchService(
                transformer,
                mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class),
                mock(EmbeddingStore.class),
                mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null,
                null,
                naverKeys,
                "",
                "",
                16L,
                30L,
                mock(PlatformTransactionManager.class),
                ratePolicy,
                webClient,
                keyResolverProvider,
                null);
        ReflectionTestUtils.setField(service, "guardProfileProps", guardProfileProps);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "similarThreshold", 0.99d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        return service;
    }

    private static ProviderCredentialResolver resolver(String... nameValuePairs) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            environment.setProperty(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return new ProviderCredentialResolver(environment);
    }

    private static void assertSecretsAbsent(String... secrets) {
        String trace = String.valueOf(TraceStore.getAll());
        for (String secret : secrets) {
            assertFalse(trace.contains(secret), trace);
        }
    }
}
