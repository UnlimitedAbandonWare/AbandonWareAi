package com.example.lms.gptsearch.web;

import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;



public abstract class AbstractWebSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractWebSearchProvider.class);

    public abstract ProviderId id();
    protected abstract WebSearchResult doSearch(WebSearchQuery q) throws Exception;

    @Override
    public final WebSearchResult search(WebSearchQuery q) {
        try {
            if (q == null || q.getQuery() == null || q.getQuery().isBlank()) {
                return new WebSearchResult(id().name(), Collections.emptyList());
            }
            return doSearch(q);
        } catch (Throwable t) {
            log.warn("[{}] web search failed: {}", id(), t.toString());
            return new WebSearchResult(id().name(), Collections.emptyList());
        }
    }
}