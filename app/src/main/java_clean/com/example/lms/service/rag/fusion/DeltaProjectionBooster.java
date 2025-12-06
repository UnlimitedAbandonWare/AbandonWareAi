package com.example.lms.service.rag.fusion;

public class DeltaProjectionBooster {
    // Simple stub: emphasize tail evidence by projecting delta
    public static double boost(double wpm, double tail){
        double d = Math.max(0.0, tail - wpm);
        return wpm + 0.5 * d;
    }
}