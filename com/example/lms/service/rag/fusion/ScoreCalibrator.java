package com.example.lms.service.rag.fusion;

/** Logistic score calibrator to normalize raw scores to [0,1]. */
public final class ScoreCalibrator {
  public double calibrate(double raw){ return 1.0/(1.0+Math.exp(-4.0*(raw-0.5))); }
}
