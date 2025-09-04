package com.example.lms.service.rag.merge;

/**
 * Simple data object representing a merged retrieval hit.  Each entry
 * contains an identifier (or URL), a title and snippet, an optional
 * score and a source label indicating whether the hit originated from
 * the web or the vector store.  Merge items are used by the
 * {@link com.example.lms.service.rag.merge.WeightedInterleaveMerger}
 * to combine multiple ranked lists while preserving diversity.
 */
public class MergeItem {
    private String id;
    private String title;
    private String snippet;
    private double score;
    private String source;

    public MergeItem() {
    }

    public MergeItem(String id, String title, String snippet, double score, String source) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.score = score;
        this.source = source;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}