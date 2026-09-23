package com.abandonware.ai.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Weighted Power Mean fuser.
 * p->infinity => max, p=1 => arithmetic mean, p->0 => geometric mean.
 * Compile-safe utility; no external deps.
 */
public final class WpmFuser {

    public double wpm(List<Double> xs, List<Double> ws, double p){
        if (xs == null || xs.isEmpty()) return 0.0d;
        if (ws == null || ws.size() != xs.size()) {
            ws = new ArrayList<>(Collections.nCopies(xs.size(), 1.0d));
        }
        double num = 0.0d, den = 0.0d;
        for (int i = 0; i < xs.size(); i++) {
            Double xv = xs.get(i);
            Double wv = ws.get(i);
            double x = Math.max(xv == null ? 0.0d : xv.doubleValue(), 1e-9);
            double w = wv == null ? 1.0d : wv.doubleValue();
            num += w * Math.pow(x, p);
            den += w;
        }
        if (den == 0.0d) return 0.0d;
        return Math.pow(num / den, 1.0d / p);
    }
}