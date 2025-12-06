package com.example.lms.guard;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.SigmoidFinalQualityGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.SigmoidFinalQualityGate
role: config
*/
public class SigmoidFinalQualityGate implements FinalQualityGate {
    @Override
    public boolean approve(double fusedScore, double onnxConf, double penalty, double rho, double k, double x0, double target) {
        double s = score(fusedScore, onnxConf, penalty, rho, k, x0);
        return s >= target;
    }
}