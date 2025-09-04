package com.example.lms.gptsearch.web.rank;

import com.example.lms.gptsearch.web.dto.WebDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the Reciprocal Rank Fusion (RRF) ranking strategy.  Each
 * document's score is the sum of 1/(k+rank) across providers, where k is a
 * configurable constant.  The service also applies optional domain allow/deny
 * boosts and penalties.
 */
@Service
public class RrfRankingService implements WebRankingService {

  private final boolean enabled;
  private final int rrfK;
  private final double allowBoost;
  private final double denyPenalty;
  private final Set<String> allowHosts;
  private final Set<String> denyHosts;

  public RrfRankingService(
      Environment env,
      @Value("${websearch.rank.enabled:true}") boolean enabled,
      @Value("${websearch.rank.rrf.k:60}") int rrfK,
      @Value("${websearch.rank.domain.allowBoost:1.2}") double allowBoost,
      @Value("${websearch.rank.domain.denyPenalty:0.5}") double denyPenalty
  ) {
    this.enabled = enabled;
    this.rrfK = Math.max(1, rrfK);
    this.allowBoost = allowBoost <= 0 ? 1.0 : allowBoost;
    this.denyPenalty = denyPenalty <= 0 ? 1.0 : denyPenalty;
    this.allowHosts = parseCsvSet(env.getProperty("websearch.rank.domain.allow", ""));
    this.denyHosts = parseCsvSet(env.getProperty("websearch.rank.domain.deny", ""));
  }

  @Override
  public List<WebDocument> rank(List<WebDocument> input, List<SourceAttribution> atts, int topK) {
    if (!enabled || input == null || input.isEmpty()) {
      return safeTopK(input, topK);
    }
    Map<String, Double> score = new HashMap<>();
    for (SourceAttribution a : atts) {
      if (a == null || a.url() == null) continue;
      double add = 1.0 / (rrfK + Math.max(1, a.providerRank()));
      score.merge(a.url(), add, Double::sum);
    }
    if (score.isEmpty()) return safeTopK(input, topK);
    // Apply domain allow/deny weighting
    for (var e : score.entrySet()) {
      String host = hostOf(e.getKey());
      if (!host.isBlank()) {
        if (!allowHosts.isEmpty() && allowHosts.contains(host)) {
          e.setValue(e.getValue() * allowBoost);
        }
        if (!denyHosts.isEmpty() && denyHosts.contains(host)) {
          e.setValue(e.getValue() * denyPenalty);
        }
      }
    }
    var dedup = dedupByUrl(input);
    dedup.sort(java.util.Comparator.comparingDouble((WebDocument d) -> score.getOrDefault(d.getUrl(), 0.0)).reversed());
    return dedup.stream().limit(Math.max(1, topK)).toList();
  }

  private static List<WebDocument> safeTopK(@Nullable List<WebDocument> input, int topK) {
    if (input == null) return List.of();
    var dedup = dedupByUrl(input);
    return dedup.stream().limit(Math.max(1, topK)).toList();
  }

  private static List<WebDocument> dedupByUrl(List<WebDocument> input) {
    LinkedHashMap<String, WebDocument> m = new LinkedHashMap<>();
    for (WebDocument d : input) {
      if (d == null || d.getUrl() == null) continue;
      m.putIfAbsent(d.getUrl(), d);
    }
    return new ArrayList<>(m.values());
  }

  private static Set<String> parseCsvSet(String csv) {
    if (csv == null || csv.isBlank()) return Set.of();
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  private static String hostOf(String url) {
    try { return java.util.Optional.ofNullable(java.net.URI.create(url).getHost()).orElse("").toLowerCase(java.util.Locale.ROOT); }
    catch (Exception ignore) { return ""; }
  }
}
