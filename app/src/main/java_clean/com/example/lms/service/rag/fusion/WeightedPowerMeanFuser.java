package com.example.lms.service.rag.fusion;

import java.util.List;

public class WeightedPowerMeanFuser {
    public static double combine(List<Double> scores, double p){
        if (scores == null || scores.isEmpty()) return 0.0;
        if (p == 0.0) {
            // geometric mean
            double prod = 1.0;
            for (double s : scores) prod *= Math.max(1e-12, s);
            return Math.pow(prod, 1.0 / scores.size());
        }
        double sum = 0.0;
        for (double s : scores) sum += Math.pow(Math.max(0.0, s), p);
        return Math.pow(sum / scores.size(), 1.0 / p);
    }
}