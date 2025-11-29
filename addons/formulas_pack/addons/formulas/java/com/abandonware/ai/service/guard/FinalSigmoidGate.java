package com.abandonware.ai.service.guard;

public final class FinalSigmoidGate {
  public double score(double x,double k,double x0){ return 1.0/(1.0+Math.exp(-k*(x-x0))); }
  public void check(double q){ double s=score(q,12.0,0.90); if(s<0.5) throw new GateRejected("Final quality below threshold"); }
}
