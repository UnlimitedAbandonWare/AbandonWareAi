package com.abandonware.ai.service.rag.metrics;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.metrics.KgMetrics
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.metrics.KgMetrics
role: config
flags: [sse]
*/
public class KgMetrics {
    private long hits;
    private long misses;
    public synchronized void incHit(boolean hit){ if (hit) hits++; else misses++; }
    public synchronized long getHits(){ return hits; }
    public synchronized long getMisses(){ return misses; }
}