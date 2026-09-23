package com.example.lms.gptsearch.web;

import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;



/**
 * A provider of live web search results.  Implementations of this
 * interface wrap external APIs such as Bing, Tavily or Google Custom
 * Search and return structured results containing snippets, titles and
 * metadata.  Providers may apply their own request/response limits and
 * should gracefully handle errors by returning empty results instead of
 * throwing exceptions.  When no API key is configured, the corresponding
 * provider should fall back to a {@link ProviderId#MOCK} implementation.
 */
public interface WebSearchProvider {

    /**
     * Execute a web search based on the given query parameters.  Providers
     * may truncate or expand the query as required by their API contracts.
     *
     * @param query The query descriptor including the search string, result
     *              count and freshness preferences
     * @return A list of search result items; never {@code null}
     */
    WebSearchResult search(WebSearchQuery query);

    /**
     * Identify this provider.  Used for logging and configuration.
     *
     * @return The provider identifier
     */
    ProviderId id();
}