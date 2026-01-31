package com.example.lms.service.rag.fusion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Collection;

/**
 * Compute a weighted power mean (generalized mean) across a collection of
 * scores.  Used by the rank fusion logic to blend scores from multiple
 * sources while preserving relative differences.
 */
@Component
public class PowerMeanFuser {
    private final double p;
    public PowerMeanFuser(@Value("${fusion.wpm.p:1.5}") double p) {
        this.p = p;
    }
    public double fuse(Collection<Double> scores) {
        if (scores == null || scores.isEmpty()) return 0.0;
        if (Math.abs(p) < 1e-9) {
            // p -> 0: geometric mean
            double s = 0; int n = 0;
            for (double v : scores) {
                s += Math.log(Math.max(1e-9, v));
                n++;
            }
            return Math.exp(s / Math.max(1, n));
        } else {
            double s = 0; int n = 0;
            for (double v : scores) {
                s += Math.pow(v, p);
                n++;
            }
            return Math.pow(s / Math.max(1, n), 1.0 / p);
        }
    }
}