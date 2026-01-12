package com.rc111.merge21.rag;

public class SearchDoc {
    public final String id;
    public final String title;
    public final String snippet;
    public final String source;
    public final double score;
    public final int rank;

    public SearchDoc(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
        this.rank = rank;
    }
}
