package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;

import java.util.Locale;


/**
 * Base class for search providers.  Provides a default fallback
 * implementation that returns an empty result.  Subclasses should
 * override {@link #doSearch(WebSearchQuery)} to call the real API.
 */
public abstract class AbstractWebSearchProvider implements WebSearchProvider {
    @Override
    public final WebSearchResult search(WebSearchQuery query) {
        try {
            return doSearch(query);
        } catch (Exception ex) {
            String providerId = id().name();
            String traceProvider = providerId.toLowerCase(Locale.ROOT);
            TraceStore.put("web." + traceProvider + ".skipped.reason", "provider-exception");
            TraceStore.put("web." + traceProvider + ".errorType", ex.getClass().getSimpleName());
            // In case of any error, return an empty result so that the
            // retrieval chain can continue.  Do not leak exception details.
            return new WebSearchResult(providerId, java.util.Collections.emptyList());
        }
    }

    /**
     * Perform the actual search.  Implementations may throw exceptions
     * which will be caught and handled by {@link #search(WebSearchQuery)}.
     */
    protected abstract WebSearchResult doSearch(WebSearchQuery query) throws Exception;
}
