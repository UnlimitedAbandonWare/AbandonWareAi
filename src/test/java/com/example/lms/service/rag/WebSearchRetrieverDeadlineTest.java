package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.filter.EducationDocClassifier;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Request-wide deadline propagation for {@link WebSearchRetriever}: an
 * exhausted or cancelled {@link TimeBudget} must stop new provider calls,
 * supplemental fan-out, and per-page scrapes. All provider/scrape calls are
 * stubbed — no external APIs.
 */
class WebSearchRetrieverDeadlineTest {

    private final WebSearchProvider provider = mock(WebSearchProvider.class);
    private final PageContentScraper scraper = mock(PageContentScraper.class);
    private final GameDomainDetector domainDetector = mock(GameDomainDetector.class);
    private final WebSearchRetriever retriever = new WebSearchRetriever(
            provider, null, scraper,
            mock(AuthorityScorer.class),
            mock(GenericDocClassifier.class),
            domainDetector,
            mock(EducationDocClassifier.class));

    @AfterEach
    void cleanup() {
        TimeBudgetContext.clear();
        TraceStore.context().clear();
    }

    private Query query(Map<String, Object> meta) {
        when(domainDetector.detect(anyString())).thenReturn("GENERAL");
        return QueryUtils.buildQuery("alpha beta", meta);
    }

    @Test
    void expiredRequestBudgetReturnsEmptyWithoutProviderCall() throws Exception {
        TimeBudgetContext.set(new TimeBudget(1));
        Thread.sleep(30L);

        List<Content> out = retriever.retrieve(query(Map.of("webTopK", 3)));

        assertTrue(out.isEmpty());
        verify(provider, never()).search(anyString(), anyInt());
        verify(scraper, never()).fetchText(anyString(), anyInt());
        assertTrue(String.valueOf(TraceStore.context().get("webSearch.disabledReason"))
                .contains("budget"));
    }

    @Test
    void scrapeLoopStopsWhenRequestBudgetDiesMidLoop() {
        TimeBudget budget = new TimeBudget(30_000);
        TimeBudgetContext.set(budget);
        when(provider.search(anyString(), anyInt())).thenReturn(List.of(
                "alpha doc http://a.example/1", "beta doc http://b.example/2"));
        // The request ends while the first page is being scraped; the second
        // page must use the snippet fallback without a new wire call.
        when(scraper.fetchText(anyString(), anyInt())).thenAnswer(inv -> {
            budget.cancel();
            return "page body text";
        });

        List<Content> out = retriever.retrieve(query(Map.of("webTopK", 2)));

        assertEquals(2, out.size());
        verify(scraper, times(1)).fetchText(anyString(), anyInt());
        assertEquals(true, TraceStore.context().get("webSearch.scrape.budgetExhausted"));
    }

    @Test
    void boundedScrapeTimeoutNeverExceedsRequestRemaining() {
        TimeBudgetContext.set(new TimeBudget(2_000));
        when(provider.search(anyString(), anyInt()))
                .thenReturn(List.of("alpha doc http://a.example/1"));
        when(scraper.fetchText(anyString(), anyInt())).thenReturn("page body");

        List<Content> out = retriever.retrieve(query(Map.of("webTopK", 1)));

        assertFalse(out.isEmpty());
        verify(scraper, times(1)).fetchText(anyString(),
                org.mockito.ArgumentMatchers.intThat(v -> v >= 1 && v <= 2000));
    }

    @Test
    void retryStopsWhenBudgetCancelledDuringProviderCall() {
        // No webBudgetMs meta: the old retry loop would run up to 3 attempts on
        // its own deadline; the request budget must stop new attempts instead.
        TimeBudget budget = new TimeBudget(2_000);
        TimeBudgetContext.set(budget);
        TraceStore.inc("web.await.events.timeout.count"); // transient signal -> retry eligible
        when(provider.search(anyString(), anyInt())).thenAnswer(inv -> {
            budget.cancel(); // caller's request ended mid-call
            return List.of();
        });

        List<Content> out = retriever.retrieve(
                query(Map.of("webTopK", 2, "webBudgetMs", 8_000L)));

        assertTrue(out.isEmpty());
        verify(provider, times(1)).search(anyString(), anyInt());
    }

    @Test
    void retryAllowedWhenTransientAndBudgetRemains() {
        TimeBudgetContext.set(new TimeBudget(30_000));
        TraceStore.inc("web.await.events.timeout.count"); // transient signal
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(provider.search(anyString(), anyInt())).thenAnswer(inv -> {
            return calls.incrementAndGet() == 1
                    ? List.of() // transient empty
                    : List.of("recovered doc http://a.example/1");
        });
        when(scraper.fetchText(anyString(), anyInt())).thenReturn("page body");

        List<Content> out = retriever.retrieve(
                query(Map.of("webTopK", 2, "webBudgetMs", 8_000L)));

        verify(provider, times(2)).search(anyString(), anyInt());
        assertFalse(out.isEmpty());
    }
}
