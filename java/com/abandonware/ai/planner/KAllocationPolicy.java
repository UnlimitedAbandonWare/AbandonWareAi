package com.abandonware.ai.planner;

public class KAllocationPolicy {
  public static final class KAlloc {
    public final int webK, vectorK, kgK;
    public KAlloc(int webK, int vectorK, int kgK){ this.webK=webK; this.vectorK=vectorK; this.kgK=kgK; }
  }
  public KAlloc alloc(boolean recencyHigh){
    return recencyHigh ? new KAlloc(15,5,3) : new KAlloc(8,12,2);
  }
}