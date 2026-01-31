package com.nova.protocol.fusion;
public final class BodeClamp {
  /** 과민 반응 제어: 값 폭주 억제 + [0,1] 클램프 */
  public static double apply(double x, double c){
    double y = (c>0) ? x / Math.sqrt(1 + c*x*x) : x;
    return Math.max(0.0, Math.min(1.0, y));
  }
}