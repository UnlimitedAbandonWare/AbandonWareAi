package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.springframework.stereotype.Component;



/**
 * Shim implementation of the Tavily provider. In OSS builds this returns
 * an empty result unless an API key is configured in a downstream fork.
 */
@Component
public class TavilyProvider extends AbstractWebSearchProvider {

    @Override
    public ProviderId id() {
        return ProviderId.TAVILY;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        // Integrate with Tavily API in downstream builds. Here we return an empty result.
        return new WebSearchResult(id().name(), java.util.Collections.emptyList());
    }
}