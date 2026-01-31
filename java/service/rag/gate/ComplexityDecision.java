// src/main/java/service/rag/gate/ComplexityDecision.java
package service.rag.gate;

import java.util.Map;

public class ComplexityDecision {
    public enum Complexity { SIMPLE, COMPLEX, NEEDS_WEB }
    private final Complexity level;
    private final double score;             // 0~1 (복합도)
    private final boolean recencySensitive; // 최신성 민감
    private final boolean domainKnown;      // 내부 도메인(사전/태그) 힌트
    private final Map<String, Double> features; // 디버그용

    public ComplexityDecision(Complexity level, double score,
                              boolean recencySensitive, boolean domainKnown,
                              Map<String, Double> features) {
        this.level = level;
        this.score = score;
        this.recencySensitive = recencySensitive;
        this.domainKnown = domainKnown;
        this.features = features;
    }
    public Complexity getLevel() { return level; }
    public double getScore() { return score; }
    public boolean isRecencySensitive() { return recencySensitive; }
    public boolean isDomainKnown() { return domainKnown; }
    public Map<String, Double> getFeatures() { return features; }
}