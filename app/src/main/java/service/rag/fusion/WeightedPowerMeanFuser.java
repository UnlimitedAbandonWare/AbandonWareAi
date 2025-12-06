package service.rag.fusion;

/** Weighted power mean (generalized mean) fuser. */
public class WeightedPowerMeanFuser {
  public double fuse(double[] scores, double[] weights, double p){
    if (scores == null || scores.length == 0) return 0.0;
    double wsum=0, num=0;
    for (int i=0;i<scores.length;i++){
      double s = scores[i];
      if (s < 0) s = 0; if (s > 1) s = 1;
      double w = (weights != null && i < weights.length)? weights[i] : 1.0;
      wsum += w; num += w*Math.pow(s, p);
    }
    double frac = num/Math.max(1e-9, wsum);
    return Math.pow(frac, 1.0/Math.max(1e-9, p));
  }
}