package com.acme.aicore.domain.model;


/**
 * Wrapper for a query destined for a vector search engine.  Conversion from
 * {@link UserQuery} is provided for convenience.
 */
public record VectorQuery(String text) {
    public static VectorQuery of(UserQuery q) {
        return new VectorQuery(q.text());
    }
}