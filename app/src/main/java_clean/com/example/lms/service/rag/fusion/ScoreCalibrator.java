package com.example.lms.service.rag.fusion;

public class ScoreCalibrator {
    private final double a;
    private final double b;
    private ScoreCalibrator(double a, double b){ this.a=a; this.b=b; }

    public static ScoreCalibrator identity(){ return new ScoreCalibrator(1.0, 0.0); }
    public static ScoreCalibrator platt(double a, double b){ return new ScoreCalibrator(a,b); }

    public double apply(double x){
        // simple affine + logistic clamp
        double y = (a * x) + b;
        return 1.0 / (1.0 + Math.exp(-y));
    }
}