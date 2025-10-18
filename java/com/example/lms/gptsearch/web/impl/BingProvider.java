package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import org.springframework.stereotype.Component;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;



/**
 * shim implementation of the Bing Web Search provider.  A real
 * implementation would construct an HTTP request to the Bing Search API
 * using the configured API key and parse the JSON response into a
 * {@link WebSearchResult}.  When no API key is available, the provider
 * should behave like the {@link MockProvider}.
 */
@Component
public class BingProvider extends AbstractWebSearchProvider {
    @Override
    public ProviderId id() {
        return ProviderId.BING;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // shim: integrate with the actual Bing Search API.
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}