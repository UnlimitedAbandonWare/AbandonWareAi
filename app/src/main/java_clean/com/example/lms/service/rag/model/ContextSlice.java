package com.example.lms.service.rag.model;

import java.util.Objects;

/**
 * Standard context slice schema: {id, title, snippet, source, score, rank}
 * Minimal immutable value object for java_clean build.
 */
public final class ContextSlice {
    private final String id;
    private final String title;
    private final String snippet;
    private final String source;
    private final double score;
    private final int rank;

    public ContextSlice(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.snippet = snippet == null ? "" : snippet;
        this.source = source == null ? "" : source;
        this.score = score;
        this.rank = rank;
    }

    public String id() { return id; }
    public String title() { return title; }
    public String snippet() { return snippet; }
    public String source() { return source; }
    public double score() { return score; }
    public int rank() { return rank; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSlice)) return false;
        ContextSlice that = (ContextSlice) o;
        return Objects.equals(id, that.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
    @Override public String toString() {
        return "ContextSlice{"
                + "id=" + id
                + ", title=" + title
                + ", source=" + source
                + ", score=" + score
                + ", rank=" + rank
                + "}";
    }
}