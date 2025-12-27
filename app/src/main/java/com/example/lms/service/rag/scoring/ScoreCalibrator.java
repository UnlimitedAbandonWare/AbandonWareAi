package com.example.lms.service.rag.scoring;

import java.util.*;
public class ScoreCalibrator {
    private double a=1.0, b=0.0; // placeholder for Platt scaling params
    public double apply(double score, String source){
        // fallback: min-max clip [0,1]
        double s = Math.max(0.0, Math.min(1.0, score));
        // simple sigmoid
        double z = a * (s*8-4) + b;
        return 1.0/(1.0+Math.exp(-z));
    }
}