package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;



/**
 * shim implementation of the Google Custom Search provider.  A complete
 * implementation would query the Google CSE API using a CX identifier and
 * an API key and map the results to {@link WebSearchResult} instances.
 */
public class GoogleCseProvider extends AbstractWebSearchProvider {
    @Override
    public ProviderId id() {
        return ProviderId.GOOGLECSE;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // shim: implement Google CSE API integration.
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}