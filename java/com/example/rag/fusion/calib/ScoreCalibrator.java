package com.example.rag.fusion.calib;


public interface ScoreCalibrator {
    void fit(double[] scores, int[] labels);
    double apply(double s);
    static ScoreCalibrator noop() { return new ScoreCalibrator() {
        public void fit(double[] s, int[] l) {}
        public double apply(double s) { return s; }
    }; }
}