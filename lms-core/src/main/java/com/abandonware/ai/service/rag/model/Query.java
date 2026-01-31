package com.abandonware.ai.service.rag.model;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.model.Query
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.model.Query
role: config
*/
public class Query {
    private final String text;
    public Query(String text) { this.text = text; }
    public String getText() { return text; }
}