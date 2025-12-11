
package com.abandonware.ai.agent.integrations;

import java.util.Set;



public class HeuristicCrossEncoder implements CrossEncoder {

    @Override
    public double score(String query, String title, String content) {
        var q = TextUtils.tokenizeSet(query);
        var t = TextUtils.tokenizeSet(title == null ? "" : title);
        var c = TextUtils.tokenizeSet(content == null ? "" : content);
        if (q.isEmpty()) return 0.0;

        double exactPhrase = containsExactPhrase(content, query) ? 1.0 : 0.0;
        double titleOverlap = overlap(q, t);
        double bodyOverlap = overlap(q, c) * 0.7;

        double raw = 0.5 * exactPhrase + 0.3 * titleOverlap + 0.2 * bodyOverlap;
        // logistic
        return 1.0 / (1.0 + Math.exp(-6 * (raw - 0.5)));
    }

    private static boolean containsExactPhrase(String text, String phrase) {
        if (text == null || phrase == null) return false;
        String a = text.toLowerCase();
        String b = phrase.toLowerCase();
        return a.contains(b) || a.contains("\"" + b + "\"");
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        return inter / Math.max(1.0, (double) a.size());
    }
}