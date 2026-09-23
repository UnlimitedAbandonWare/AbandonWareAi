package com.abandonware.ai.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tail-weighted power mean aggregator (for hyper-risk modes).
 * Uses top-α fraction of signals then applies WPM(p).
 */
public final class TailWeightedPowerMean {

    public double aggregate(List<Double> xs, double alpha, double p){
        if (xs == null || xs.isEmpty()) return 0.0d;
        List<Double> sorted = new ArrayList<>(xs);
        sorted.sort(Comparator.reverseOrder());
        int k = Math.max(1, (int) Math.ceil(alpha * sorted.size()));
        List<Double> head = sorted.subList(0, k);
        List<Double> ws = new ArrayList<>(Collections.nCopies(head.size(), 1.0d));
        return new WpmFuser().wpm(head, ws, p);
    }
}