package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import org.springframework.stereotype.Component;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * shim implementation of the Bing Web Search provider.  A real
 * implementation would construct an HTTP request to the Bing Search API
 * using the configured API key and parse the JSON response into a
 * {@link WebSearchResult}.  When no API key is available, the provider
 * should behave like the {@link MockProvider}.
 */
@Component
public class BingProvider extends AbstractWebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(BingProvider.class);
    @Override
    public ProviderId id() {
        return ProviderId.BING;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // [Patch] Bing Search is currently disabled.  To avoid hard failures when
        // no API key or quota is configured, this stub returns an empty result so
        // that the WebSearchRetriever can fall back to other providers (e.g. NAVER/BRAVE).
        if (query != null && query.getQuery() != null) {
            log.warn("[BingProvider] Bing Search is DISABLED by policy. Returning empty result for query={}", query.getQuery());
        } else {
            log.warn("[BingProvider] Bing Search is DISABLED by policy. Returning empty result for <null> query.");
        }
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}