package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AdaptiveWebSearchHandlerProviderLocaleTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void requestedProviderRoutingIsLocaleStable() {
        Locale original = Locale.getDefault();

        assertAll(
                () -> assertEquals(List.of(ProviderId.TAVILY),
                        dispatchedProviders("tavily", Locale.forLanguageTag("tr-TR"))),
                () -> assertEquals(List.of(ProviderId.TAVILY),
                        dispatchedProviders("tavily", Locale.US)),
                () -> assertEquals(List.of(ProviderId.TAVILY),
                        dispatchedProviders("TAVILY", Locale.forLanguageTag("tr-TR"))),
                () -> assertEquals(List.of(ProviderId.TAVILY, ProviderId.NAVER),
                        dispatchedProviders("not-a-provider", Locale.forLanguageTag("tr-TR"))),
                () -> assertEquals(original, Locale.getDefault()));
    }

    private static List<ProviderId> dispatchedProviders(String providerHint, Locale locale) {
        Locale previous = Locale.getDefault();
        List<ProviderId> calls = new ArrayList<>();
        try {
            Locale.setDefault(locale);
            AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                    new SearchDecisionService(),
                    List.of(
                            new RecordingProvider(ProviderId.TAVILY, calls),
                            new RecordingProvider(ProviderId.NAVER, calls)),
                    mock(PageContentScraper.class),
                    mock(RelevanceScoringService.class),
                    mock(DomainProfileLoader.class));
            Query query = QueryUtils.buildQuery("provider routing locale control",
                    Map.of(
                            "useWebSearch", true,
                            "searchMode", "FORCE_LIGHT",
                            "webProviders", providerHint));

            handler.handle(query, new ArrayList<Content>());
            return List.copyOf(calls);
        } finally {
            Locale.setDefault(previous);
            TraceStore.clear();
        }
    }

    private record RecordingProvider(ProviderId id, List<ProviderId> calls) implements WebSearchProvider {
        @Override
        public WebSearchResult search(WebSearchQuery query) {
            calls.add(id);
            return new WebSearchResult(id.name(), List.of());
        }
    }
}
