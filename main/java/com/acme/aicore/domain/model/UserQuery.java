package com.acme.aicore.domain.model;


/**
 * Simple wrapper for the user’s natural language query.  A query is
 * represented by its text and may be extended to include additional
 * metadata in the future (e.g. language, timestamp, or intent).
 */
public record UserQuery(String text) {
    public static UserQuery of(String text) {
        return new UserQuery(text);
    }
}