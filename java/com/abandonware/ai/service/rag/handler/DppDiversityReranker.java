package com.abandonware.ai.service.rag.handler;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.*;

@Component
public class DppDiversityReranker {

  public <T> List<T> rerank(String query, List<T> in,
                           int k, int maxCandidates, double lambda,
                           java.util.function.ToDoubleFunction<T> scoreFn,
                           java.util.function.Function<T,String> snippetFn,
                           java.util.function.Function<T,String> idFn) {
    if (in == null || in.isEmpty()) return in;
    final int K = Math.min(k, in.size());
    final List<T> pool = in.stream()
        .sorted(Comparator.comparingDouble(scoreFn).reversed())
        .limit(Math.min(maxCandidates, in.size()))
        .collect(Collectors.toList());

    List<T> out = new ArrayList<>();
    Set<String> selected = new HashSet<>();

    while (out.size() < K && !pool.isEmpty()) {
      T best = null;
      double bestGain = -1;

      for (T cand : pool) {
        double rel = normalize(scoreFn.applyAsDouble(cand), pool, scoreFn);
        double maxSim = out.stream()
            .mapToDouble(r -> jaccard3gram(snippetFn.apply(cand), snippetFn.apply(r)))
            .max().orElse(0.0);
        double gain = lambda * rel + (1 - lambda) * (1.0 - maxSim);
        if (gain > bestGain) { bestGain = gain; best = cand; }
      }
      out.add(best);
      selected.add(idFn.apply(best));
      pool.remove(best);
    }
    return out;
  }

  private <T> double normalize(double s, List<T> pool, java.util.function.ToDoubleFunction<T> scoreFn) {
    double min = pool.stream().mapToDouble(scoreFn).min().orElse(0);
    double max = pool.stream().mapToDouble(scoreFn).max().orElse(1);
    return (s - min) / Math.max(1e-9, max - min);
  }

  private double jaccard3gram(String a, String b) {
    Set<String> A = shingles(a, 3), B = shingles(b, 3);
    int inter = 0; for (String t : A) if (B.contains(t)) inter++;
    int union = A.size() + B.size() - inter;
    return union == 0 ? 0 : (double) inter / union;
  }

  private Set<String> shingles(String s, int n) {
    if (s == null) return Collections.emptySet();
    s = s.toLowerCase(Locale.ROOT);
    Set<String> set = new HashSet<>();
    for (int i = 0; i <= s.length() - n; i++) set.add(s.substring(i, i + n));
    return set;
  }
}
