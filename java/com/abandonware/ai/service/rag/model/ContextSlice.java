package com.abandonware.ai.service.rag.model;

import java.util.Objects;

/** Standard context contract: {id, title, snippet, source, score, rank} */
public class ContextSlice {
    private String id;
    private String title;
    private String snippet;
    private String source;
    private double score;
    private int rank;

    public ContextSlice() {}

    public ContextSlice(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
        this.rank = rank;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSlice)) return false;
        ContextSlice that = (ContextSlice) o;
        return Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return Objects.hash(id); }

    @Override public String toString() {
        return "ContextSlice(id=" + id + ", title=" + title + ", score=" + score + ", rank=" + rank + ")";
    }
}