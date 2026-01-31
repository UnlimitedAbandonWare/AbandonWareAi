package com.abandonware.ai.planner;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.planner.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.planner.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {
  public static final class KAlloc {
    public final int webK, vectorK, kgK;
    public KAlloc(int webK, int vectorK, int kgK){ this.webK=webK; this.vectorK=vectorK; this.kgK=kgK; }
  }
  public KAlloc alloc(boolean recencyHigh){
    return recencyHigh ? new KAlloc(15,5,3) : new KAlloc(8,12,2);
  }
}