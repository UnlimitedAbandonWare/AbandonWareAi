package com.example.lms.service.rag.fusion;

import java.util.Map;
import java.util.Objects;

/**
 * TEMP SHIM — Replace with lms-core’s real ContextSlice.
 * Standard fields per project contract: {id,title,snippet,source,score,rank}.
 */
public class ContextSlice {
    private final String id;
    private final String title;
    private final String snippet;
    private final String source;
    private final Double score;
    private final Integer rank;
    private final String locale;
    private final Map<String, Object> meta;

    public ContextSlice(String id, String title, String snippet, String source,
                        Double score, Integer rank) {
        this(id, title, snippet, source, score, rank, null, null);
    }
    public ContextSlice(String id, String title, String snippet, String source,
                        Double score, Integer rank, String locale, Map<String,Object> meta) {
        this.id = id; this.title = title; this.snippet = snippet;
        this.source = source; this.score = score; this.rank = rank;
        this.locale = locale; this.meta = meta;
    }

    // Both JavaBean-style and record-style accessors for broader compatibility
    public String id() { return id; }
    public String getId() { return id; }
    public String title() { return title; }
    public String getTitle() { return title; }
    public String snippet() { return snippet; }
    public String getSnippet() { return snippet; }
    public String source() { return source; }
    public String getSource() { return source; }
    public Double score() { return score; }
    public Double getScore() { return score; }
    public Integer rank() { return rank; }
    public Integer getRank() { return rank; }
    public String locale() { return locale; }
    public String getLocale() { return locale; }
    public Map<String,Object> meta() { return meta; }
    public Map<String,Object> getMeta() { return meta; }

    public String stableKey() {
        return (source != null ? source : "") + "::" + (id != null ? id : title);
    }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSlice)) return false;
        return Objects.equals(stableKey(), ((ContextSlice) o).stableKey());
    }
    @Override public int hashCode() { return Objects.hash(stableKey()); }
    @Override public String toString() {
        return "ContextSlice{"+stableKey()+", score="+score+", rank="+rank+"}";
    }
}
