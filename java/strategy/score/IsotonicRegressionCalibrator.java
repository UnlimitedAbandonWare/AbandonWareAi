package strategy.score;

import java.util.*;
public class IsotonicRegressionCalibrator implements ScoreCalibrator {
  private final double[] xs; private final double[] ys;
  public IsotonicRegressionCalibrator(double[] xs, double[] ys) { this.xs=xs; this.ys=ys; }
  @Override public double calibrate(double raw) {
    int i = Arrays.binarySearch(xs, raw);
    if (i>=0) return ys[i];
    int p = -i-1;
    if (p<=0) return ys[0];
    if (p>=xs.length) return ys[ys.length-1];
    // linear interp
    double x0=xs[p-1], x1=xs[p], y0=ys[p-1], y1=ys[p];
    double t=(raw-x0)/(x1-x0);
    return y0 + t*(y1-y0);
  }
  public static IsotonicRegressionCalibrator fromKnots(List<double[]> knots) {
    double[] xs=new double[knots.size()]; double[] ys=new double[knots.size()];
    for(int i=0;i<knots.size();i++){ xs[i]=knots.get(i)[0]; ys[i]=knots.get(i)[1]; }
    return new IsotonicRegressionCalibrator(xs, ys);
  }
}