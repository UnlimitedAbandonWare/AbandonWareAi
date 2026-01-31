package com.example.lms.guard;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.FinalQualityGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.FinalQualityGate
role: config
*/
public interface FinalQualityGate {
    boolean approve(double fusedScore, double onnxConf, double penalty, double rho, double k, double x0, double target);
    default double score(double fusedScore, double onnxConf, double penalty, double rho, double k, double x0) {
        double Q = rho * fusedScore + (1.0 - rho) * onnxConf - penalty;
        return 1.0 / (1.0 + Math.exp(-k * (Q - x0)));
    }
}