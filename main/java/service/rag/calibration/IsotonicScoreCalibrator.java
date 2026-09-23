package service.rag.calibration;

/** 간이 단조 보정: piecewise-linear (x_i, y_i), x 증가→y 증가 보장 */
public class IsotonicScoreCalibrator {
  private final double[] xs, ys;
  public IsotonicScoreCalibrator(double[] xs, double[] ys){
    this.xs=xs; this.ys=ys;
  }
  public double calibrate(double x){
    if (xs == null || ys == null || xs.length == 0 || ys.length == 0) return x;
    if (x<=xs[0]) return ys[0];
    for(int i=1;i<xs.length;i++){
      if (x<=xs[i]) {
        double t=(x-xs[i-1])/(xs[i]-xs[i-1]);
        return ys[i-1]+t*(ys[i]-ys[i-1]);
      }
    }
    return ys[ys.length-1];
  }
}