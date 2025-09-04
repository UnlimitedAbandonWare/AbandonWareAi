package com.example.lms.search;

import com.example.lms.dto.LinkDto;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reciprocal Rank Fusion (RRF) utility for fusing heterogeneous ranked lists.
 * See Cormack, Clarke (2009).  Score is sum(1/(K + rank_i)).
 */
public final class RerankUtil {
    private RerankUtil() {}
    public static List<LinkDto> rrfFuse(List<LinkDto> web, List<LinkDto> vector, int topN) {
        return rrfFuse(web, vector, topN, 60);
    }
    public static List<LinkDto> rrfFuse(List<LinkDto> web, List<LinkDto> vector, int topN, int K) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, LinkDto> canon = new HashMap<>();
        if (web != null) {
            int i = 0;
            for (LinkDto l : web) {
                String key = canonical(l);
                scores.merge(key, 1.0 / (K + (i++ + 1)), Double::sum);
                canon.putIfAbsent(key, l);
            }
        }
        if (vector != null) {
            int i = 0;
            for (LinkDto l : vector) {
                String key = canonical(l);
                scores.merge(key, 1.0 / (K + (i++ + 1)), Double::sum);
                canon.putIfAbsent(key, l);
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));
        return ranked.stream()
                .limit(topN)
                .map(e -> {
                    LinkDto base = canon.get(e.getKey());
                    return LinkDto.builder()
                            .title(base != null ? base.getTitle() : null)
                            .url(base != null ? base.getUrl() : null)
                            .source("web+vector")
                            .score(e.getValue())
                            .build();
                }).collect(Collectors.toList());
    }
    private static String canonical(LinkDto l) {
        String u = l != null && l.getUrl() != null ? l.getUrl() : "";
        // Drop fragment to reduce duplicates
        int idx = u.indexOf('#');
        if (idx >= 0) u = u.substring(0, idx);
        return u.trim().toLowerCase(Locale.ROOT);
    }
}
