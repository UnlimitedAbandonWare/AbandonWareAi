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
    MOCK;
}