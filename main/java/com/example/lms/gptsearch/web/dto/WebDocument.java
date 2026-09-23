package com.example.lms.gptsearch.web.dto;

import java.time.Instant;



/**
 * Represents a single web search hit returned by a provider.  Each
 * document includes a URL, title, a short snippet of text and optional
 * credibility and timestamp metadata.  Additional fields can be added
 * later as needed.
 */
public class WebDocument {
    private String url;
    private String title;
    private String snippet;
    /** Credibility score between 0 and 1 where higher is more trusted */
    private Double credibility;
    /** Optional publication timestamp of the document */
    private Instant timestamp;

    public WebDocument() {
    }

    public WebDocument(String url, String title, String snippet, Double credibility, Instant timestamp) {
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.credibility = credibility;
        this.timestamp = timestamp;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    public Double getCredibility() {
        return credibility;
    }

    public void setCredibility(Double credibility) {
        this.credibility = credibility;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}