package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;

/**
 * Stub implementation of the Tavily search provider.  This provider
 * illustrates how additional providers can be plugged into the search
 * framework.  Real API calls are not implemented here.
 */
public class TavilyProvider extends AbstractWebSearchProvider {
    @Override
    public ProviderId id() {
        return ProviderId.TAVILY;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // TODO: Integrate with Tavily API when available
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}