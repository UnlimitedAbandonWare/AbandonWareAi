package com.nova.protocol.fusion;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.fusion.ScoreCalibrator
role: config
*/
public class ScoreCalibrator {
    /** 간단한 로지스틱 보정 슬롯: p = 1/(1+exp(-a*(s-b))) */
    private final double a, b;
    public ScoreCalibrator() { this(1.0, 0.0); }
    public ScoreCalibrator(double a, double b) { this.a = a; this.b = b; }
    public double calibrate(double rawScore) {
        double x = a * (rawScore - b);
        return 1.0 / (1.0 + Math.exp(-x));
    }
}