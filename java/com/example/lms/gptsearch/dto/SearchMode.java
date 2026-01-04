package com.example.lms.gptsearch.dto;


/**
 * Enumeration of search execution modes for the GPT Web Search plugin.  These
 * values control whether and how the system performs live web queries when
 * answering a user request.  The modes align with the GPT Pro agent design
 * documented in the upgrade instructions.
 *
 * <ul>
 *   <li>{@code AUTO}: The system decides whether to search based on query
 *   complexity, risk and user preferences.  This is the default behaviour.
 *   The assistant may choose to perform a lightweight or deep search or skip
 *   search entirely.</li>
 *   <li>{@code OFF}: Disables live web search.  Only existing internal
 *   retrieval (memory, vector, history) will be used.</li>
 *   <li>{@code FORCE_LIGHT}: Forces a single lightweight web query with short
 *   snippets.  Useful for simple fact lookups without incurring the cost of
 *   deep self-ask decomposition.</li>
 *   <li>{@code FORCE_DEEP}: Forces a comprehensive search using self-ask
 *   decomposition, multiple queries and verification.  Suitable for complex
 *   comparative or high-risk questions.</li>
 * </ul>
 */
public enum SearchMode {
    /**
     * Automatic mode.  The agent determines whether to run a search and
     * chooses between light and deep strategies based on heuristics and LLM
     * signals.
     */
    AUTO,
    /**
     * Disable live web search.  Only use internal sources such as
     * vector stores, memory or history.
     */
    OFF,
    /**
     * Force a lightweight search (single query, small snippet size) before
     * performing any further retrieval.
     */
    FORCE_LIGHT,
    /**
     * Force a deep search with self-ask decomposition and multi-query
     * verification.
     */
    FORCE_DEEP;
}