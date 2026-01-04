package com.abandonware.ai.addons.model;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.model.ContextSlice
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.addons.model.ContextSlice
role: config
*/
public class ContextSlice {
    public String id;
    public String title;
    public String snippet;
    public String source;
    public double score;
    public int rank;

    public ContextSlice() {}
    public ContextSlice(String id, String title, String snippet, String source, double score, int rank) {
        this.id=id; this.title=title; this.snippet=snippet; this.source=source; this.score=score; this.rank=rank;
    }
}