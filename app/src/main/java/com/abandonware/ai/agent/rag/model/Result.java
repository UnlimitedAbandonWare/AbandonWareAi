
package com.abandonware.ai.agent.rag.model;
public class Result {
    private String id;
    private String title;
    private String snippet;
    private String source; // web|vector|kg|memory|ocr
    private double score;
    private int rank;
    public Result(){}
    public Result(String id, String title, String snippet, String source, double score, int rank) {
        this.id = id; this.title = title; this.snippet = snippet; this.source = source; this.score = score; this.rank = rank;
    }
    // getters/setters
    public String getId() { return id; } public void setId(String id) { this.id=id; }
    public String getTitle() { return title; } public void setTitle(String title) { this.title=title; }
    public String getSnippet() { return snippet; } public void setSnippet(String snippet) { this.snippet=snippet; }
    public String getSource() { return source; } public void setSource(String source) { this.source=source; }
    public double getScore() { return score; } public void setScore(double score) { this.score=score; }
    public int getRank() { return rank; } public void setRank(int rank) { this.rank=rank; }
}
