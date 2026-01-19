package com.example.lms.replay;

import java.util.Collections;
import java.util.List;
import java.util.Objects;



/**
 * A simple POJO representing a single query replay entry.  Each record
 * contains the original query text, the list of ground truth document
 * identifiers (or other unique identifiers) that are considered relevant,
 * the list of ranked document identifiers returned by the retrieval
 * pipeline, and an optional measured latency in milliseconds.  At a
 * minimum the query and ranked results must be provided.  If ground
 * truth is absent the evaluation metrics will treat the entry as having
 * zero relevance.
 */
public final class ReplayRecord {
    private final String query;
    private final List<String> groundTruth;
    private final List<String> rankedResults;
    private final long latencyMs;

    /**
     * Construct a new replay record.  If either groundTruth or rankedResults
     * are null they will be replaced with an empty immutable list to
     * simplify downstream usage.  The latency is optional and may be zero
     * when unknown.
     *
     * @param query         the user query represented by this record
     * @param groundTruth   the list of relevant identifiers (may be null)
     * @param rankedResults the ranked results returned by the system (may be null)
     * @param latencyMs     the latency in milliseconds (may be 0)
     */
    public ReplayRecord(String query, List<String> groundTruth, List<String> rankedResults, long latencyMs) {
        this.query = Objects.requireNonNullElse(query, "");
        this.groundTruth = groundTruth == null ? Collections.emptyList() : List.copyOf(groundTruth);
        this.rankedResults = rankedResults == null ? Collections.emptyList() : List.copyOf(rankedResults);
        this.latencyMs = Math.max(0, latencyMs);
    }

    public String getQuery() {
        return query;
    }

    public List<String> getGroundTruth() {
        return groundTruth;
    }

    public List<String> getRankedResults() {
        return rankedResults;
    }

    public long getLatencyMs() {
        return latencyMs;
    }
}