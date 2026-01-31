package com.nova.protocol.rag.narrow;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AngerOverdriveNarrower - multi-stage reduction with anchor keyword bias.
 * Backward compatible with the old narrow(List<String>) API.
 */
public class AngerOverdriveNarrower {

    /** Legacy API kept for backward compatibility. */
    public List<String> narrow(List<String> in) {
        return narrow(null, in);
    }

    /** New API: uses anchors derived from query. */
    public List<String> narrow(String query, List<String> in) {
        if (in == null) return List.of();
        List<String> items = new ArrayList<>(in);
        // Extract up to 2 anchor tokens from query
        Set<String> anchors = extractAnchors(query, 2);

        // Score = relevance (anchor hit count) * pseudo-authority (shorter string -> higher)
        List<Scored> scored = new ArrayList<>();
        for (String s : items) {
            int rel = 0;
            String low = s == null ? "" : s.toLowerCase(Locale.ROOT);
            for (String a : anchors) if (!a.isEmpty() && low.contains(a)) rel++;
            double authority = 1.0 / Math.max(8, (s == null ? 0 : s.length()));
            double score = rel * 1.0 + authority * 0.2;
            scored.add(new Scored(s, score));
        }
        scored.sort((a,b)->Double.compare(b.score, a.score));

        // Multi-stage cuts: 48 -> 32 -> 16 -> 8 -> 4 -> 2
        List<String> cur = scored.stream().map(sc -> sc.value).collect(Collectors.toList());
        int[] cuts = new int[]{48,32,16,8,4,2};
        for (int c : cuts) {
            if (cur.size() <= c) continue;
            cur = cur.subList(0, c);
        }
        return cur;
    }

    private static Set<String> extractAnchors(String q, int k){
        if (q == null || q.isBlank()) return Set.of();
        String[] toks = q.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣_]+");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : toks) {
            if (t.length() < 2) continue;
            set.add(t);
            if (set.size() >= k) break;
        }
        return set;
    }

    private static final class Scored{
        final String value; final double score;
        Scored(String v, double s){ this.value=v; this.score=s; }
    }
}