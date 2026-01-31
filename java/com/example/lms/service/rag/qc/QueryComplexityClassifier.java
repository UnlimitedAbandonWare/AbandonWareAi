package com.example.lms.service.rag.qc;

import java.util.Set;

/**
 * Tiny, fast query complexity classifier.
 * Returns a score in [0,1] and a bucket LOW/MEDIUM/HIGH using heuristics:
 * - token length
 * - presence of quotes/time expressions
 * - number of separators and entities (poor man's proxy)
 */
public class QueryComplexityClassifier {

    public static class Result {
        public final double score;
        public final String bucket;
        public Result(double s, String b) { score=s; bucket=b; }
    }

    public Result classify(String query) {
        if (query == null || query.isBlank()) return new Result(0.0, "LOW");
        String q = query.trim();
        int len = q.split("\\s+").length;
        int quotes = count(q, '"') + count(q, '\'');
        int seps = count(q, ',') + count(q, ';') + count(q, ':');
        int ents = countUpperTokens(q);
        int timeHints = containsAny(q.toLowerCase(), Set.of("today","yesterday","last","since","until","between","202","201","Q1","Q2","Q3","Q4")) ? 1 : 0;
        double s = norm(len, 1, 40) * 0.45 + norm(quotes+seps, 0, 6) * 0.25 + norm(ents, 0, 5) * 0.2 + (timeHints>0?0.1:0);
        String bucket = (s < 0.33) ? "LOW" : (s < 0.66 ? "MEDIUM" : "HIGH");
        return new Result(Math.max(0.0, Math.min(1.0, s)), bucket);
    }

    private static int count(String s, char c){ int n=0; for (int i=0;i<s.length();i++) if (s.charAt(i)==c) n++; return n; }
    private static int countUpperTokens(String q) {
        String[] toks = q.split("[\u0020\u3000\\s]+");
        int n=0;
        for (String t: toks) {
            if (t.length()>=2 && Character.isUpperCase(t.codePointAt(0))) n++;
        }
        return n;
    }
    private static boolean containsAny(String s, Set<String> needles){ for(String n: needles){ if (s.contains(n)) return true; } return false; }
    private static double norm(double v, double a, double b) { if (b<=a) return 0.0; double x=(v-a)/(b-a); if (x<0) x=0; if (x>1) x=1; return x; }
}