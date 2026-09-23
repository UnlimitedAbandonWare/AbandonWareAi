package com.abandonware.ai.agent.nn;

import java.util.*;



public class EnsembleEvaluator {

    public static class Stats {
        public double min, max, mean, std;
        public List<Double> zscore;
    }

    public static Stats stats(List<Double> xs){
        Stats s = new Stats();
        if (xs == null || xs.isEmpty()){
            s.min = s.max = s.mean = s.std = 0.0;
            s.zscore = List.of();
            return s;
        }
        List<Double> finite = new ArrayList<>(xs.size());
        for (Double v : xs) {
            if (v != null && Double.isFinite(v)) {
                finite.add(v);
            }
        }
        if (finite.isEmpty()) {
            s.min = s.max = s.mean = s.std = 0.0;
            s.zscore = List.of();
            return s;
        }
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0.0;
        for (double v : finite){
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        double mean = sum / finite.size();
        double var = 0.0;
        for (double v : finite){
            double d = v - mean;
            var += d*d;
        }
        double std = Math.sqrt(var / finite.size());
        List<Double> z = new ArrayList<>(finite.size());
        for (double v : finite){
            z.add(std == 0.0 ? 0.0 : (v - mean)/std);
        }
        s.min = min; s.max = max; s.mean = mean; s.std = std; s.zscore = Collections.unmodifiableList(z);
        return s;
    }
}
