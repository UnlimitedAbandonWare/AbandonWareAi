
package com.example.lms.planning;

import java.util.Map;



public class ComplexityScore {
    public enum Label { SIMPLE, COMPLEX, WEB_RECENT }
    private final Label label;
    private final double score; // 0..1 certainty
    private final Map<String, Double> features;

    public ComplexityScore(Label label, double score, Map<String, Double> features) {
        this.label = label;
        this.score = score;
        this.features = features;
    }
    public Label getLabel() { return label; }
    public double getScore() { return score; }
    public Map<String, Double> getFeatures() { return features; }
}