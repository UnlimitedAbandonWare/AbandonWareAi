package com.acme.aicore.domain.model;


/**
 * Wrapper for a query destined for a web search provider.  Conversion from
 * {@link UserQuery} is provided for convenience.
 */
public record WebSearchQuery(String text) {
    public static WebSearchQuery of(UserQuery q) {
        return new WebSearchQuery(q.text());
    }
}