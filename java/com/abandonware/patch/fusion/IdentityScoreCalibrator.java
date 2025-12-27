package com.abandonware.patch.fusion;
public class IdentityScoreCalibrator implements ScoreCalibrator {
    @Override public double calibrate(double rawScore) { return Math.max(0.0, Math.min(1.0, rawScore)); }
}