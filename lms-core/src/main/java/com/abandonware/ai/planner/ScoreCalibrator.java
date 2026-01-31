package com.abandonware.ai.planner;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.planner.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.planner.ScoreCalibrator
role: config
*/
public class ScoreCalibrator {
  public double calibrate(double raw, double min, double max) {
    if (Double.isNaN(raw)) return 0.0;
    double denom = Math.max(1e-9, max - min);
    double x = (raw - min) / denom;
    if (x < 0) return 0.0;
    if (x > 1) return 1.0;
    return x;
  }
}