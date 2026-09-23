
package com.example.lms.risk;


public class RiskDecisionIndex {
    public final double rdi; // 0..1 higher means riskier
    public RiskDecisionIndex(double rdi) {
        this.rdi = Double.isFinite(rdi) ? Math.max(0, Math.min(1, rdi)) : 0.0;
    }
}
