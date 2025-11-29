package service.rag.fusion;

import java.util.Arrays;

/**
 * Simple isotonic-like monotonic calibration (PAV-inspired, single pass approximation).
 * Input: array of raw scores per source; Output: normalized 0..1, monotonic non-decreasing.
 */
public class ScoreCalibrator {
  public double[] isotonic(double[] x) {
    if (x == null || x.length == 0) return new double[0];
    int n = x.length; double[] y = Arrays.copyOf(x, n);
    double mn = y[0], mx = y[0];
    for (double v : y){ if (v < mn) mn = v; if (v > mx) mx = v; }
    double denom = Math.max(1e-9, mx - mn);
    for (int i=0;i<n;i++) y[i] = (y[i]-mn)/denom;

    // pooled adjacent violators (very simplified)
    double[] w = new double[n]; Arrays.fill(w, 1.0);
    for (int i=0;i<n-1;i++){
      if (y[i] > y[i+1]) {
        double avg = (w[i]*y[i] + w[i+1]*y[i+1])/(w[i]+w[i+1]);
        y[i]=y[i+1]=avg; w[i]+=w[i+1]; w[i+1]=0;
        for (int j=i-1;j>=0 && y[j]>y[j+1];j--){
          double a=(w[j]*y[j]+w[j+1]*y[j+1])/(w[j]+w[j+1]);
          y[j]=y[j+1]=a; w[j]+=w[j+1]; w[j+1]=0;
        }
      }
    }
    return y;
  }
}