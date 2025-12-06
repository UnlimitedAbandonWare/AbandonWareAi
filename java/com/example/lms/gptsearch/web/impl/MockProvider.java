package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;



/**
 * Mock search provider that returns no results.  This is used when no API
 * keys are configured or for unit testing the web search integration.
 */
public class MockProvider extends AbstractWebSearchProvider {
    @Override
    public ProviderId id() {
        return ProviderId.MOCK;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}