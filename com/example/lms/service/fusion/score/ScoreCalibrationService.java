package com.example.lms.service.fusion.score; 
public final class ScoreCalibrationService {
  private final IsotonicCalibrator iso = new IsotonicCalibrator();
  public double calibrate(String source,double raw){ return iso.calibrate(raw, source); }
}
