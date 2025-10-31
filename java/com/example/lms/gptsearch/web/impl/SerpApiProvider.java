package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;



/**
 * shim implementation of the SerpAPI provider.  This implementation
 * currently returns no results; real integration would delegate to the
 * SerpAPI client library and parse the response.
 */
public class SerpApiProvider extends AbstractWebSearchProvider {
    @Override
    public ProviderId id() {
        return ProviderId.SERPAPI;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // shim: implement SerpAPI integration.
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}