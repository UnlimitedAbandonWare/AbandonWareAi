package com.nova.protocol.rag.explore;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extreme-Z: trigger + simple expansion + selection range helper.
 * String-centric implementation to fit current prototypes.
 */
public class ExtremeZHandler {

    private static final Pattern URGENCY = Pattern.compile("(?i)(urgent|asap|긴급|지금|즉시|중요)");

    public static final class InitStats {
        public final int candidates;
        public final double contradictionMean;
        public InitStats(int candidates, double contradictionMean){
            this.candidates = candidates; this.contradictionMean = contradictionMean;
        }
    }

    /** Trigger policy: (scarce && contradictory) || urgent keywords */
    public boolean shouldFire(InitStats s, String q,
                              int minCandidates, double contradictionThreshold){
        boolean scarce = s.candidates < Math.max(1, minCandidates);
        boolean contradict = s.contradictionMean >= contradictionThreshold;
        boolean urgent = q != null && URGENCY.matcher(q).find();
        return (scarce && contradict) || urgent;
    }

    /** Very small self-ask + burst: split into up to 3 sub-questions and expand with n-grams. */
    public List<String> expandQuestions(String q, int selfAskK, int burstN){
        if (q == null || q.isBlank()) return List.of();
        String[] parts = q.split("[?.,;]+\\s*");
        List<String> subs = new ArrayList<>();
        for (int i = 0; i < Math.min(selfAskK, parts.length); i++){
            String p = parts[i].trim();
            if (!p.isEmpty()) subs.add(p);
        }
        // naive burst: generate variants by adding simple modifiers
        String[] mods = new String[]{"definition", "latest", "example", "참고", "원문"};
        List<String> burst = new ArrayList<>();
        for (String s : subs){
            burst.add(s);
            for (String m : mods){
                burst.add(s + " " + m);
                if (burst.size() >= burstN) break;
            }
            if (burst.size() >= burstN) break;
        }
        return burst;
    }

    /** Select a window [low, high) from expanded queries (existing helper kept). */
    public List<String> selectRange(List<String> expanded, int low, int high) {
        if (expanded == null || expanded.isEmpty()) return expanded;
        int l = Math.max(0, Math.min(low, expanded.size()-1));
        int h = Math.max(l, Math.min(high, expanded.size()));
        return expanded.subList(l, h).stream().collect(Collectors.toList());
    }
}