package com.example.lms.resume;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.resume.ResumeMatchScorer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.resume.ResumeMatchScorer
role: config
*/
public class ResumeMatchScorer {
  public static class Features {
    public double jaccard, dense, quantity, recency, authority;
  }
  public double score(Features f){
    List<Double> xs = List.of(f.jaccard, f.dense, f.quantity, f.recency, f.authority);
    double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    return Math.tanh(mean);
  }
}