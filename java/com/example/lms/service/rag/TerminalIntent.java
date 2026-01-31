package com.example.lms.service.rag;


/**
 * Enumerates special intents that terminate the retrieval pipeline.  When a
 * user's query is classified into one of these categories the retrieval
 * chain should short-circuit and allow a dedicated handler (e.g. location
 * or authentication handler) to generate the response.  The set of
 * terminal intents is configurable via the {@code retrieval.terminal-intents}
 * property in {@code application.yml}.  This enum defines canonical names
 * for location requests, authentication flows and health check probes.
 */
public enum TerminalIntent {
    /** Queries asking for the user's current location, e.g. "나 지금 어디야?" */
    LOCATION,
    /** Requests requiring authentication or authorisation. */
    AUTH,
    /** Health check and diagnostics requests. */
    HEALTHCHECK
}