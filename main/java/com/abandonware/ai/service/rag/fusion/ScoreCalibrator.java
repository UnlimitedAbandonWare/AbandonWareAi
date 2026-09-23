package com.abandonware.ai.service.rag.fusion;

public interface ScoreCalibrator {
    /**
     * Normalize raw scores from heterogeneous sources into a [0,1] band.
     * @param raw input score
     * @param source source id such as "web", "vector", "kg"
     * @return normalized score in [0,1]
     */
    double normalize(double raw, String source);
}