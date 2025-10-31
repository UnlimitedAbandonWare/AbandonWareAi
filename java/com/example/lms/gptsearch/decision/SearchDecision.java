package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.web.ProviderId;
import java.util.List;



/**
 * Encapsulates the output of the search decision engine.  A decision
 * determines whether a search should be executed, the depth (LIGHT or
 * DEEP), the providers to use and the number of results to fetch.
 */
public class SearchDecision {
    private final boolean shouldSearch;
    private final Depth depth;
    private final List<ProviderId> providers;
    private final int topK;
    private final String reason;

    public enum Depth { LIGHT, DEEP }

    public SearchDecision(boolean shouldSearch, Depth depth, List<ProviderId> providers, int topK, String reason) {
        this.shouldSearch = shouldSearch;
        this.depth = depth;
        this.providers = providers;
        this.topK = topK;
        this.reason = reason;
    }

    public boolean shouldSearch() {
        return shouldSearch;
    }

    public Depth depth() {
        return depth;
    }

    public List<ProviderId> providers() {
        return providers;
    }

    public int topK() {
        return topK;
    }

    public String reason() {
        return reason;
    }
}