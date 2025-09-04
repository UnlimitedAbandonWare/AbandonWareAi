package com.example.lms.gptsearch.dto;

/**
 * Enumeration controlling the visibility of search details and links.
 * When {@code EXPOSED} the chat API will return fused web/vector links and
 * render the full search trace to the client.  In {@code QUIET} mode links
 * and trace information are omitted from both sync and streaming responses.
 * The default value is QUIET.
 */
public enum SearchVisibility {
    EXPOSED,
    QUIET
}
