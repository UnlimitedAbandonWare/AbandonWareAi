 package com.example.lms.service.disambiguation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DisambiguationResult {
    private String ambiguousTerm;
    private String resolvedIntent;
    private String rewrittenQuery;
    private String confidence; // "low" | "medium" | "high" or free text
    private Double score;      // 0.0 ~ 1.0 (선택)

    public String getAmbiguousTerm() { return ambiguousTerm; }
    public void setAmbiguousTerm(String ambiguousTerm) { this.ambiguousTerm = ambiguousTerm; }
    public String getResolvedIntent() { return resolvedIntent; }
    public void setResolvedIntent(String resolvedIntent) { this.resolvedIntent = resolvedIntent; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    /** 운영 기준: score ≥ 0.65 또는 confidence == "high" 계열이면 신뢰함 */
    public boolean isConfident() {
        if (score != null) return score >= 0.65;
        if (confidence == null) return false;
        String c = confidence.trim().toLowerCase();
        return c.contains("high") || c.contains("confident") || c.contains("sure");
    }
}
