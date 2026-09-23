package com.example.lms.gptsearch.web.dto;

import com.example.lms.gptsearch.web.ProviderId;
import java.time.Duration;
import java.util.List;



/**
 * Describes a web search request.  At a minimum the query string and
 * desired number of results must be provided.  Additional optional
 * parameters allow specifying freshness preferences and provider hints.
 */
public class WebSearchQuery {
    /** The search terms */
    private final String query;
    /** Number of results to fetch per provider */
    private final int topK;
    /** Preferred providers or null to use defaults */
    private final List<ProviderId> providers;
    /** Maximum age of results (optional); null means any age */
    private final Duration freshness;

    public WebSearchQuery(String query, int topK, List<ProviderId> providers, Duration freshness) {
        this.query = query;
        this.topK = topK;
        this.providers = providers;
        this.freshness = freshness;
    }

    public String getQuery() {
        return query;
    }

    public int getTopK() {
        return topK;
    }

    public List<ProviderId> getProviders() {
        return providers;
    }

    public Duration getFreshness() {
        return freshness;
    }
}