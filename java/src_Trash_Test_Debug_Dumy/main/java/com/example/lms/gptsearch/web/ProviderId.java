package com.example.lms.gptsearch.web;

/**
 * Enumeration of supported web search providers.  The ordering of values in
 * this enum does not imply priority; actual provider preference should be
 * configured via application properties or user request parameters.
 */
public enum ProviderId {
    /** Microsoft Bing Web Search API */
    BING,
    /** Tavily AI search service */
    TAVILY,
    /** Google Custom Search Engine */
    GOOGLECSE,
    /** SerpAPI aggregator */
    SERPAPI,
    /** Mock provider for testing or when API keys are missing */
    MOCK,
    /**
     * NAVER Web Search provider.  This provider wraps the existing
     * {@link com.example.lms.service.NaverSearchService} to expose Naver
     * results through the {@link com.example.lms.gptsearch.web.WebSearchProvider}
     * interface.  Adding this enum constant allows the adaptive search
     * layer to reference Naver as a first‑class provider.
     */
    NAVER,
    /**
     * Azure Cognitive Search provider.  This provider invokes the Azure
     * Cognitive Search REST API when enabled via configuration and
     * returns results structured as {@link com.example.lms.gptsearch.web.dto.WebSearchResult}.
     * See {@link com.example.lms.gptsearch.web.impl.AzureProvider} for implementation details.
     */
    AZURE;
}