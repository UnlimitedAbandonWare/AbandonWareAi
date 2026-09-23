package com.example.lms.nova;


public class BravePlan {
    public final boolean enabled;
    public final int webTopK;
    public final int vectorTopK;
    public final int kgTopK;
    public final boolean burstEnabled;
    public final int burstMin;
    public final int burstMax;
    public final int minCitations;
    public final String order;
    public final String authorityTier;

    public BravePlan(boolean enabled, int webTopK, int vectorTopK, int kgTopK,
                     boolean burstEnabled, int burstMin, int burstMax,
                     int minCitations, String order, String authorityTier) {
        this.enabled = enabled;
        this.webTopK = webTopK;
        this.vectorTopK = vectorTopK;
        this.kgTopK = kgTopK;
        this.burstEnabled = burstEnabled;
        this.burstMin = burstMin;
        this.burstMax = burstMax;
        this.minCitations = minCitations;
        this.order = order;
        this.authorityTier = authorityTier;
    }
}