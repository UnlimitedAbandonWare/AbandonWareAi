package com.example.lms.config;

/**
 * Centralised feature flags with sensible defaults.
 */
public class FeatureFlags {
    public boolean bm25Enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    public double rrfWeightWeb = Double.parseDouble(System.getProperty("fusion.rrf.weight.web", "1.0"));
    public double rrfWeightVector = Double.parseDouble(System.getProperty("fusion.rrf.weight.vector", "1.0"));
    public double rrfWeightKg = Double.parseDouble(System.getProperty("fusion.rrf.weight.kg", "0.8"));
    public double rrfWeightBm25 = Double.parseDouble(System.getProperty("fusion.rrf.weight.bm25", "1.0"));
}