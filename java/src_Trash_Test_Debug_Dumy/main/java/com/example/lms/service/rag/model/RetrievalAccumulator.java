package com.example.lms.service.rag.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulator object used during hybrid retrieval.  The accumulator tracks
 * whether the chain has been terminated, stores flags indicating whether
 * web and vector retrieval succeeded, holds weighted interleave settings
 * and captures intermediate hit lists for both sources.  When the chain
 * completes the lists may be merged into a final result.
 */
public class RetrievalAccumulator {

    /** Whether the chain terminated early. */
    private boolean terminated;
    /** Human readable reason for termination. */
    private String terminateReason;
    /** True when the web retrieval stage produced results. */
    private Boolean webOk;
    /** True when the vector retrieval stage produced results. */
    private Boolean vectorOk;
    /** Weight assigned to the web results (0.0–1.0). */
    private double webWeight = 0.5;
    /** Weight assigned to the vector results (0.0–1.0). */
    private double vectorWeight = 0.5;
    /** Overall routing decision for the query. */
    private RouteDecision routeDecision = RouteDecision.HYBRID;
    /** Hits returned from the web search. */
    private List<com.example.lms.service.rag.merge.MergeItem> webHits = new ArrayList<>();
    /** Hits returned from the vector search. */
    private List<com.example.lms.service.rag.merge.MergeItem> vectorHits = new ArrayList<>();
    /** Requested maximum number of hits. */
    private int topK = 10;

    public boolean isTerminated() {
        return terminated;
    }

    public void setTerminated(boolean terminated) {
        this.terminated = terminated;
    }

    public String getTerminateReason() {
        return terminateReason;
    }

    public void setTerminateReason(String terminateReason) {
        this.terminateReason = terminateReason;
    }

    public Boolean getWebOk() {
        return webOk;
    }

    public void setWebOk(Boolean webOk) {
        this.webOk = webOk;
    }

    public Boolean getVectorOk() {
        return vectorOk;
    }

    public void setVectorOk(Boolean vectorOk) {
        this.vectorOk = vectorOk;
    }

    public double getWebWeight() {
        return webWeight;
    }

    public void setWebWeight(double webWeight) {
        this.webWeight = Math.max(0.0, Math.min(1.0, webWeight));
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = Math.max(0.0, Math.min(1.0, vectorWeight));
    }

    public RouteDecision getRouteDecision() {
        return routeDecision;
    }

    public void setRouteDecision(RouteDecision routeDecision) {
        this.routeDecision = routeDecision;
    }

    public List<com.example.lms.service.rag.merge.MergeItem> getWebHits() {
        return webHits;
    }

    public void setWebHits(List<com.example.lms.service.rag.merge.MergeItem> webHits) {
        this.webHits = webHits;
    }

    public List<com.example.lms.service.rag.merge.MergeItem> getVectorHits() {
        return vectorHits;
    }

    public void setVectorHits(List<com.example.lms.service.rag.merge.MergeItem> vectorHits) {
        this.vectorHits = vectorHits;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }
}