package com.example.rag.fusion.calib;

import java.util.*;


/**
 * Simple Isotonic Regression (PAV) for score calibration.
 * Labels are 0/1; scores are continuous. Produces a piecewise-constant
 * non-decreasing mapping; we then linearly interpolate at bin boundaries.
 */
public final class IsotonicCalibrator implements ScoreCalibrator {
    private double[] x_ = new double[]{0.0, 1.0};
    private double[] y_ = new double[]{0.0, 1.0};
    private boolean fitted = false;

    @Override
    public void fit(double[] scores, int[] labels) {
        if (scores == null || labels == null || scores.length != labels.length || scores.length == 0) {
            fitted = false; return;
        }
        int n = scores.length;
        double[][] pairs = new double[n][3]; // score, count, mean(label)
        for (int i=0;i<n;i++){
            pairs[i][0] = scores[i];
            pairs[i][1] = 1.0;
            pairs[i][2] = labels[i];
        }
        Arrays.sort(pairs, java.util.Comparator.comparingDouble(a -> a[0]));

        // PAV: pool adjacent violators
        List<double[]> blocks = new ArrayList<>();
        for (double[] p : pairs) blocks.add(new double[]{p[1], p[2]}); // [count, mean]
        int i=0;
        while (i < blocks.size()-1) {
            if (blocks.get(i)[1] <= blocks.get(i+1)[1]) { i++; continue; }
            // merge
            double c1=blocks.get(i)[0], m1=blocks.get(i)[1];
            double c2=blocks.get(i+1)[0], m2=blocks.get(i+1)[1];
            double c=c1+c2, m=(c1*m1+c2*m2)/c;
            blocks.set(i, new double[]{c, m});
            blocks.remove(i+1);
            i = Math.max(0, i-1);
        }

        // Build x (sorted unique scores) and y (cumulative means mapped)
        // We'll distribute blocks uniformly over the sorted scores
        x_ = new double[blocks.size()+2];
        y_ = new double[blocks.size()+2];
        x_[0] = pairs[0][0]; y_[0] = blocks.get(0)[1];
        for (int b=0; b<blocks.size(); b++){
            // approximate representative x at quantile of the block
            int idx = 1 + b;
            double t = (double)(b+1)/(double)(blocks.size()+1);
            int si = Math.min((int)Math.floor(t*(n-1)), n-1);
            x_[idx] = pairs[si][0];
            y_[idx] = blocks.get(b)[1];
        }
        x_[x_.length-1] = pairs[n-1][0];
        y_[y_.length-1] = blocks.get(blocks.size()-1)[1];
        // ensure monotonic non-decreasing y
        for (int j=1; j<y_.length; j++) {
            if (y_[j] < y_[j-1]) y_[j] = y_[j-1];
        }
        fitted = true;
    }

    @Override
    public double apply(double s) {
        if (!fitted) return s;
        int n = x_.length;
        if (s <= x_[0]) return y_[0];
        if (s >= x_[n-1]) return y_[n-1];
        int lo=0, hi=n-1;
        while (lo+1 < hi) {
            int mid = (lo+hi)/2;
            if (s >= x_[mid]) lo = mid; else hi = mid;
        }
        // linear interpolation
        double t = (s - x_[lo]) / (x_[hi] - x_[lo] + 1e-9);
        return y_[lo]*(1.0-t) + y_[hi]*t;
    }
}