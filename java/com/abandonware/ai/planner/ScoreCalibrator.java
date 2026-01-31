package com.abandonware.ai.planner;

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