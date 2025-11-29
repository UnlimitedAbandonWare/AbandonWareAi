package com.abandonware.ai.service.rag.fusion;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.ScoreCalibrator
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.ScoreCalibrator
role: config
flags: [kg]
*/
public interface ScoreCalibrator {
    /**
     * Normalize raw scores from heterogeneous sources into a [0,1] band.
     * @param raw input score
     * @param source source id such as "web", "vector", "kg"
     * @return normalized score in [0,1]
     */
    double normalize(double raw, String source);
}