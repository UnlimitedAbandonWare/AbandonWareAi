package com.example.lms.gptsearch.web;

import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.Locale;



public abstract class AbstractWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractWebSearchProvider.class);

    public abstract ProviderId id();
    protected abstract WebSearchResult doSearch(WebSearchQuery q) throws Exception;

    protected WebSearchResult blankQueryResult(WebSearchQuery q) {
        traceBlankQuery(q);
        return new WebSearchResult(id().name(), Collections.emptyList());
    }

    private void traceBlankQuery(WebSearchQuery q) {
        String query = q == null ? null : q.getQuery();
        int requested = q == null ? 0 : Math.max(0, q.getTopK());
        String prefix = "web." + id().name().toLowerCase(Locale.ROOT);
        com.example.lms.search.TraceStore.put(prefix + ".requestedCount", requested);
        com.example.lms.search.TraceStore.put(prefix + ".returnedCount", 0);
        com.example.lms.search.TraceStore.put(prefix + ".afterFilterCount", 0);
        com.example.lms.search.TraceStore.put(prefix + ".zeroResults", true);
        com.example.lms.search.TraceStore.put(prefix + ".providerDisabled", false);
        com.example.lms.search.TraceStore.put(prefix + ".providerEmpty", false);
        com.example.lms.search.TraceStore.put(prefix + ".afterFilterStarved", false);
        com.example.lms.search.TraceStore.put(prefix + ".skipped.reason", "blank_query");
        com.example.lms.search.TraceStore.put(prefix + ".failureReason", "blank_query");
        com.example.lms.search.TraceStore.put(prefix + ".queryHash",
                query == null ? "" : com.example.lms.trace.SafeRedactor.hashValue(query));
        com.example.lms.search.TraceStore.put(prefix + ".queryLength", query == null ? 0 : query.length());
    }

    @Override
    public final WebSearchResult search(WebSearchQuery q) {
        try {
            if (q == null || q.getQuery() == null || q.getQuery().isBlank()) {
                return blankQueryResult(q);
            }
            return doSearch(q);
        } catch (Throwable t) {
            String query = q == null ? null : q.getQuery();
            traceProviderSearchError(q, t);
            log.warn("[AWX][search][provider] web search failed provider={} failureReason={} errorType={} queryHash12={} queryLength={}",
                    id(),
                    "provider-search-error",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), "unknown"),
                    com.example.lms.trace.SafeRedactor.hash12(query),
                    query == null ? 0 : query.length());
            return new WebSearchResult(id().name(), Collections.emptyList());
        }
    }

    private void traceProviderSearchError(WebSearchQuery q, Throwable t) {
        String query = q == null ? null : q.getQuery();
        int requested = q == null ? 0 : Math.max(0, q.getTopK());
        String errorType = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(
                t == null ? "unknown" : t.getClass().getSimpleName(),
                "unknown");
        String prefix = "web." + id().name().toLowerCase(Locale.ROOT);
        com.example.lms.search.TraceStore.put(prefix + ".requestedCount", requested);
        com.example.lms.search.TraceStore.put(prefix + ".returnedCount", 0);
        com.example.lms.search.TraceStore.put(prefix + ".afterFilterCount", 0);
        com.example.lms.search.TraceStore.put(prefix + ".zeroResults", false);
        com.example.lms.search.TraceStore.put(prefix + ".providerDisabled", false);
        com.example.lms.search.TraceStore.put(prefix + ".providerEmpty", false);
        com.example.lms.search.TraceStore.put(prefix + ".afterFilterStarved", false);
        com.example.lms.search.TraceStore.put(prefix + ".failureReason", "provider-search-error");
        com.example.lms.search.TraceStore.put(prefix + ".errorType", errorType);
        com.example.lms.search.TraceStore.put(prefix + ".queryHash",
                query == null ? "" : com.example.lms.trace.SafeRedactor.hashValue(query));
        com.example.lms.search.TraceStore.put(prefix + ".queryLength", query == null ? 0 : query.length());
    }
}
