package com.example.lms.service.guard; 
public final class FinalQualityGate {
  private final double k,x0,tau; 
  public FinalQualityGate(double k,double x0,double tau){this.k=k;this.x0=x0;this.tau=tau;}
  public boolean allow(double x){ double s=1.0/(1.0+Math.exp(-k*(x-x0))); return s>=tau; }
}
