package service.rag.fusion;
public class WeightedPowerMeanFuser {
  public double fuse(double[] w, double[] x, double p){
    double num=0, den=0;
    for(int i=0;i<x.length;i++){ num += w[i]*Math.pow(x[i], p); den += w[i]; }
    return Math.pow(num/Math.max(1e-9, den), 1.0/Math.max(1e-9, p));
  }
}