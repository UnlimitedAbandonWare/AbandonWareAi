package com.example.lms.service;

/**
 * Exception thrown when the Naver web search API fails.  This allows callers
 * to distinguish between a real "no results" condition and an underlying
 * network or API error.  When this exception is thrown the controller
 * should gracefully degrade into a RAG-only mode.
 */
public class NaverSearchException extends RuntimeException {

    private final String query;

    /**
     * Construct a new exception with the provided message and query.
     *
     * @param message a human-readable error message
     * @param query   the search query that triggered the failure
     */
    public NaverSearchException(String message, String query) {
        super(message);
        this.query = query;
    }

    /**
     * Construct a new exception with the provided message, query and cause.
     *
     * @param message a human-readable error message
     * @param query   the search query that triggered the failure
     * @param cause   the underlying cause of the failure
     */
    public NaverSearchException(String message, String query, Throwable cause) {
        super(message, cause);
        this.query = query;
    }

    /**
     * Return the query associated with this failure.
     *
     * @return the offending search query
     */
    public String getQuery() {
        return query;
    }
}