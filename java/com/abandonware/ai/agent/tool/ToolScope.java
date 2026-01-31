package com.abandonware.ai.agent.tool;


/**
 * Enumeration of the supported tool scopes.  A scope represents a specific
 * permission that must be granted by the user (via a consent token) before
 * invoking a tool that may have side effects or access sensitive data.  The
 * names and values of these scopes are fixed by the tool manifest and must
 * not be changed without updating the manifest.
 */
public enum ToolScope {
    /** Permission to push a message into a Kakao channel or conversation. */
    KAKAO_PUSH("kakao.push"),
    /** Permission to send a callback payload to an n8n webhook. */
    N8N_NOTIFY("n8n.notify"),
    /** Permission to perform an outbound web query. */
    WEB_GET("web.get"),
    /** Permission to invoke the Kakao Local Search API to look up nearby places. */
    PLACES_READ("places.read"),
    /** Permission to reverse geocode coordinates to an address. */
    GEO_READ("geo.read"),
    /** Permission to query internal embeddings/RAG store. */
    INTERNAL_READ("internal.read"),
    /** Permission to enqueue a long running internal job. */
    INTERNAL_ENQUEUE("internal.enqueue");

    private final String value;

    ToolScope(String value) {
        this.value = value;
    }

    /**
     * Returns the canonical string value of this scope, as defined in the tool
     * manifest.  This is the value that appears in the consent card and
     * everywhere else outside of code.
     */
    public String value() {
        return value;
    }
}