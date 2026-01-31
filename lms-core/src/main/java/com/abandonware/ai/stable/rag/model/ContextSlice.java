package com.abandonware.ai.stable.rag.model;

import java.util.Objects;

/**
 * Minimal context slice used by fusion/rerank layers.
 * This is a **clean** implementation intended to compile in isolation.
 */
public final class ContextSlice {
    private final String id;
    private final String title;
    private final String snippet;
    private final String source; // web|vector|kg|other
    private final double score;
    private final int rank;

    public ContextSlice(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id;
        this.title = title;
        this.snippet = snippet;
        this.source = source;
        this.score = score;
        this.rank = rank;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSnippet() { return snippet; }
    public String getSource() { return source; }
    public double getScore() { return score; }
    public int getRank() { return rank; }

    public ContextSlice withScore(double newScore){ return new ContextSlice(id,title,snippet,source,newScore,rank); }
    public ContextSlice withRank(int newRank){ return new ContextSlice(id,title,snippet,source,score,newRank); }

    @Override public boolean equals(Object o){
        if(this==o) return true;
        if(!(o instanceof ContextSlice)) return false;
        ContextSlice other = (ContextSlice)o;
        return Objects.equals(id, other.id);
    }
    @Override public int hashCode(){ return Objects.hash(id); }
    @Override public String toString(){
        return "ContextSlice{id="+id+", title="+title+", score="+score+", rank="+rank+"}";
    }
}