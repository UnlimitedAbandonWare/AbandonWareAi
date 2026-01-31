package service.rag.fusion;
public class ScoreCalibrator {
  public double[] minMax(double[] xs){
    if (xs == null || xs.length == 0) return new double[0];
    double min = xs[0], max = xs[0];
    for (double v : xs){ if (v<min) min=v; if (v>max) max=v; }
    double span = Math.max(1e-9, max-min);
    double[] out = new double[xs.length];
    for(int i=0;i<xs.length;i++) out[i] = (xs[i]-min)/span;
    return out;
  }
}