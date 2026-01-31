package service.rag.fusion;

import java.util.List;

public class WeightedPowerMean {

    public double fuse(List<Double> xs, List<Double> ws, double p) {
        if (xs == null || ws == null || xs.isEmpty() || ws.isEmpty() || xs.size() != ws.size()) return 0.0;
        double num = 0.0, den = 0.0;
        for (int i = 0; i < xs.size(); i++) {
            num += ws.get(i) * Math.pow(xs.get(i), p);
            den += ws.get(i);
        }
        if (den == 0.0) return 0.0;
        return Math.pow(num / den, 1.0 / p);
    }
}