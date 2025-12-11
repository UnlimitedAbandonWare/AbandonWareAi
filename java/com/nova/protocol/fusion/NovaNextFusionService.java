package com.nova.protocol.fusion;
import java.util.*;
public class NovaNextFusionService {
    public static class ScoredResult {
        private double score;
        public ScoredResult() {}
        public ScoredResult(double s) { this.score = s; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
    public List<ScoredResult> fuse(List<ScoredResult> in) { return in; }
}