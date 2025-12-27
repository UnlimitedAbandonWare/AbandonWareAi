package com.example.lms.service.orchestration;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.orchestration.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.orchestration.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {
  private final double webRatio, vecRatio, kgRatio;
  public KAllocationPolicy(double webRatio, double vecRatio, double kgRatio) {
    double s = Math.max(1e-6, webRatio + vecRatio + kgRatio);
    this.webRatio = webRatio/s; this.vecRatio = vecRatio/s; this.kgRatio = kgRatio/s;
  }
  public int web(PlannerNexus.QueryContext qc){ return (int)Math.max(1, Math.round(qc.budgetK() * webRatio)); }
  public int vector(PlannerNexus.QueryContext qc){ return (int)Math.max(1, Math.round(qc.budgetK() * vecRatio)); }
  public int kg(PlannerNexus.QueryContext qc){ return (int)Math.max(0, Math.round(qc.budgetK() * kgRatio)); }
}