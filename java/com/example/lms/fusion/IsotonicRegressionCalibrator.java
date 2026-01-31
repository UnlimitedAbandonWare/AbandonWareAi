
package com.example.lms.fusion;

import java.util.*;



/** Simple Pool-Adjacent-Violators (PAV) isotonic regression for score calibration. */
public class IsotonicRegressionCalibrator {
    private double[] x = new double[0];
    private double[] y = new double[0];
    private boolean trained = false;

    public void fit(double[] scores, double[] targets) {
        if (scores.length != targets.length || scores.length == 0) throw new IllegalArgumentException("Bad data");
        int n = scores.length;
        double[][] data = new double[n][2];
        for (int i=0;i<n;i++) { data[i][0]=scores[i]; data[i][1]=targets[i]; }
        Arrays.sort(data, Comparator.comparingDouble(a->a[0]));
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Integer> ws = new ArrayList<>();
        for (double[] d: data) {
            xs.add(d[0]); ys.add(d[1]); ws.add(1);
            int m = ys.size();
            while (m>=2 && ys.get(m-2) > ys.get(m-1)) {
                double y1 = ys.remove(m-2);
                double y2 = ys.remove(m-2);
                int w1 = ws.remove(m-2);
                int w2 = ws.remove(m-2);
                double merged = (y1*w1 + y2*w2) / (w1+w2);
                ys.add(merged);
                ws.add(w1+w2);
                m = ys.size();
            }
        }
        this.x = xs.stream().mapToDouble(Double::doubleValue).toArray();
        this.y = ys.stream().mapToDouble(Double::doubleValue).toArray();
        this.trained = true;
    }

    public double predict(double s) {
        if (!trained) return s;
        if (s <= x[0]) return y[0];
        if (s >= x[x.length-1]) return y[y.length-1];
        for (int i=1;i<x.length;i++) {
            if (s <= x[i]) {
                double t = (s - x[i-1]) / (x[i] - x[i-1] + 1e-9);
                return y[i-1] + t*(y[i]-y[i-1]);
            }
        }
        return y[y.length-1];
    }
}