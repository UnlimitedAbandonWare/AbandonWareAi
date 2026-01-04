package com.abandonware.ai.service.rag.metrics;

public class KgMetrics {
    private long hits;
    private long misses;
    public synchronized void incHit(boolean hit){ if (hit) hits++; else misses++; }
    public synchronized long getHits(){ return hits; }
    public synchronized long getMisses(){ return misses; }
}