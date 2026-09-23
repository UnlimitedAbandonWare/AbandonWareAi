package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.auth.DomainWhitelist;
import com.example.lms.service.rag.extract.PageContentScraper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AdaptiveWebSearchHandlerProviderTraceTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void tracesRequestedRegisteredResolvedAndMissingProvidersWithoutRawQuery() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private provider trace query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", "FORCE_LIGHT",
                        "webProviders", "GOOGLECSE,TAVILY"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(List.of("GOOGLECSE", "TAVILY"), TraceStore.get("web.adaptive.requestedProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.registeredProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.resolvedProviders"));
        assertEquals(List.of("GOOGLECSE"), TraceStore.get("web.adaptive.missingProviders"));
        assertEquals(2, TraceStore.get("web.adaptive.requestedProviderCount"));
        assertEquals(1, TraceStore.get("web.adaptive.missingProviderCount"));
    }

    @Test
    void searchModeMetadataIsTrimmedAndCaseInsensitive() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private provider trace query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", " force_light ",
                        "webProviders", "TAVILY"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.requestedProviders"));
        assertEquals(List.of("TAVILY"), TraceStore.get("web.adaptive.resolvedProviders"));
    }

    @Test
    void invalidMetaIntegerAndProviderIdLeaveRedactedTraceBreadcrumbs() {
        TraceStore.clear();
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new EmptyProvider(ProviderId.TAVILY)),
                mock(PageContentScraper.class),
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class)
        );

        Query query = QueryUtils.buildQuery("private adaptive parser query",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", "FORCE_LIGHT",
                        "webTopK", "not-a-number",
                        "webProviders", "not-a-provider"
                ));

        handler.handle(query, new ArrayList<Content>());

        assertEquals(Boolean.TRUE, TraceStore.get("web.adaptive.suppressed.metaInt"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.adaptive.suppressed.providerId"));
    }

    @Test
    void failSoftCatchSitesUseRedactedTraceBreadcrumbs() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java"));

        assertEquals(1, count(source, "traceSuppressed(\"domainProfile\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"financeUri\", e)"));
        assertEquals(1, count(source, "traceSuppressed(\"financeFilter\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"precisionFetch\", ex)"));
        assertEquals(1, count(source, "traceSuppressed(\"snippetFetch\", ignore)"));
        assertEquals(1, count(source, "traceSuppressed(\"providerTrace\", ignore)"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualOfficialOnlyHandlerRejectsCommunityWithProfileButAcceptsWhitelistFallback(boolean profilePresent) {
        TraceStore.clear();
        DomainWhitelist whitelist = new DomainWhitelist();
        whitelist.setEnableDomainFilter(true);
        whitelist.setDomainAllowlist(List.of("openai.com"));
        DomainProfileLoader profiles = new DomainProfileLoader(whitelist);
        profiles.load();
        String communityUrl = "https://namu.wiki/synthetic-domain-policy";
        WebDocument document = new WebDocument(communityUrl, "Synthetic domain policy", "Synthetic snippet", null, null);
        java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
        WebSearchProvider provider = new WebSearchProvider() {
            @Override
            public ProviderId id() {
                return ProviderId.TAVILY;
            }

            @Override
            public WebSearchResult search(WebSearchQuery query) {
                providerCalls.incrementAndGet();
                return new WebSearchResult(id().name(), List.of(document));
            }
        };
        PageContentScraper scraper = mock(PageContentScraper.class);
        RelevanceScoringService relevance = mock(RelevanceScoringService.class);
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(), List.of(provider), scraper, relevance,
                profilePresent ? profiles : null);
        handler.domainWhitelist = whitelist;
        Query query = QueryUtils.buildQuery("synthetic domain policy query", Map.of(
                "useWebSearch", true, "searchMode", "FORCE_LIGHT", "webProviders", "TAVILY",
                "officialOnly", true));
        List<Content> accumulator = new ArrayList<>();

        handler.handle(query, accumulator);

        int expectedUsed = profilePresent ? 0 : 1;
        assertEquals(1, providerCalls.get());
        assertEquals(expectedUsed, accumulator.size());
        assertEquals("adaptive_web", TraceStore.get("retrieval.integrity.source"));
        assertEquals(1, TraceStore.get("retrieval.integrity.inputCount"));
        assertEquals(0, TraceStore.get("retrieval.integrity.urlMissingCount"));
        assertEquals(1 - expectedUsed, TraceStore.get("retrieval.integrity.policyDeniedCount"));
        assertEquals(1 - expectedUsed, TraceStore.get("retrieval.integrity.filteredCount"));
        assertEquals(expectedUsed, TraceStore.get("retrieval.integrity.finalUsedCount"));
        assertEquals("none", TraceStore.get("retrieval.integrity.fallbackStage"));
        if (profilePresent) {
            assertEquals("hard_policy_filtered_empty", TraceStore.get("retrieval.integrity.emptyReason"));
        } else {
            assertNull(TraceStore.get("retrieval.integrity.emptyReason"));
            assertEquals(communityUrl, accumulator.get(0).textSegment().metadata().getString("url"));
            assertTrue(accumulator.get(0).textSegment().text().contains("Synthetic snippet"));
        }
        verifyNoInteractions(scraper, relevance);
    }

    private static long count(String source, String needle) {
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle))
                .matcher(source)
                .results()
                .count();
    }

    private record EmptyProvider(ProviderId id) implements WebSearchProvider {
        @Override
        public WebSearchResult search(WebSearchQuery query) {
            return new WebSearchResult(id.name(), List.of());
        }
    }
}
