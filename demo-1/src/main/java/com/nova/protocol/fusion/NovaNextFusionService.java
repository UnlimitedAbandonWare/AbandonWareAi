package com.nova.protocol.fusion;
import java.util.*;
/**
 * Lightweight local stub to satisfy compile-time for RRF hypernova fusion.
 * This can be replaced by the real NovaNextFusionService implementation.
 */
public class NovaNextFusionService {
    public static class ScoredResult {
        private double score;
        public ScoredResult() {}
        public ScoredResult(double score) { this.score = score; }
        public double getScore() { return score; }
        public void setScore(double s) { this.score = s; }
    }
    public List<ScoredResult> fuse(List<ScoredResult> in) {
        // Identity fuse for now.
        return in;
    }
}