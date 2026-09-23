package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "gpt-search.bing", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BingProvider extends AbstractWebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(BingProvider.class);
    @Override
    public ProviderId id() {
        return ProviderId.BING;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // Bing is optional and may not be available in ops environments.
        // This shim returns an empty result so the orchestrator can proceed
        // with other providers (NAVER/BRAVE/...) without hard failure.
        if (query != null && query.getQuery() != null) {
            log.debug("[BingProvider] no-op (empty result) for queryLen={}", query.getQuery().length());
        }
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}