package com.abandonware.ai.agent.tool;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.ToolScope
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.ToolScope
role: config
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