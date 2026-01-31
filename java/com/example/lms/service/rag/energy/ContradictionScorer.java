package com.example.lms.service.rag.energy;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two text fragments contradiction score approximation.
 * - Prefer heuristic score to reduce LLM cost/timeouts.
 * - Optional LLM scoring via fastChatModel with NightmareBreaker wrapper.
 */
@Component
public class ContradictionScorer {

    private static final Pattern FIRST_NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern NUMBERS = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern NEGATION = Pattern.compile("(\\bnot\\b|\\bno\\b|없(다|음|어요|습니다)?|못(하|했|해)|금지|불가|아니(다|요)?|반대)");
    private static final Pattern POSITIVE = Pattern.compile("(\\byes\\b|\\bok\\b|있(다|음|어요|습니다)?|가능|허용|맞(다|음|아요)?|된다)");

    private static final int CACHE_MAX = 512;
    private final Map<Long, Double> lru = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHE_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
                    return size() > CACHE_MAX;
                }
            }
    );

    @Autowired(required = false)
    @Qualifier("fastChatModel")
    private ChatModel chatModel;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Value("${rag.overdrive.contradiction.use-llm:false}")
    private boolean useLlm;

    public double score(String a, String b) {
        if (a == null || b == null) return 0.0;

        String aa = a.trim();
        String bb = b.trim();
        if (aa.isEmpty() || bb.isEmpty()) return 0.0;
        if (aa.equals(bb)) return 0.0;

        long key = symmetricKey(aa, bb);
        Double cached = lru.get(key);
        if (cached != null) return cached;

        // 1) heuristic-first to avoid unnecessary LLM calls
        double heuristic = heuristicScore(aa, bb);

        // 2) LLM off / unavailable -> heuristic only
        if (!useLlm || chatModel == null) {
            lru.put(key, heuristic);
            return heuristic;
        }

        // 3) breaker open -> heuristic only + trace
        if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.RAG_CONTRADICTION_SCORE)) {
            TraceStore.append("aux.failures", "contradiction_breaker_open");
            lru.put(key, heuristic);
            return heuristic;
        }

        // 4) LLM score (fail-soft)
        try {
            Double out = (nightmareBreaker != null)
                    ? nightmareBreaker.execute(
                            NightmareKeys.RAG_CONTRADICTION_SCORE,
                            "ContradictionScorer",
                            () -> llmScore(aa, bb, heuristic),
                            v -> v == null || Double.isNaN(v),
                            () -> heuristic
                    )
                    : llmScore(aa, bb, heuristic);

            double v = clamp(out == null ? heuristic : out, 0.0, 1.0);
            lru.put(key, v);
            TraceStore.put("rag.contradiction.score", v);
            return v;
        } catch (Exception e) {
            TraceStore.append("aux.failures", "contradiction_exception: " + e.getClass().getSimpleName());
            lru.put(key, heuristic);
            return heuristic;
        }
    }

    private Double llmScore(String a, String b, double heuristic) {
        String prompt = """
                두 텍스트가 서로 모순되는지 0~1 점수로 평가하세요.
                0=모순 없음, 1=강한 모순. 숫자만 출력하세요.
                (힌트: 휴리스틱=%s)
                A: %s
                B: %s
                """.formatted(String.format(java.util.Locale.ROOT, "%.3f", heuristic), a, b);

        String raw = chatModel.chat(List.of(UserMessage.from(prompt))).aiMessage().text();
        double parsed = parseNumber0to1(raw);
        if (Double.isNaN(parsed)) {
            if (nightmareBreaker != null) {
                nightmareBreaker.recordSilentFailure(
                        NightmareKeys.RAG_CONTRADICTION_SCORE,
                        snippet(prompt, 220),
                        "non_numeric_or_blank"
                );
            }
            TraceStore.append("aux.failures", "contradiction_non_numeric");
            return heuristic;
        }
        return parsed;
    }

    private static double heuristicScore(String a, String b) {
        String la = normalize(a);
        String lb = normalize(b);

        // quick similarity -> low contradiction
        double j = jaccard(tokenize(la), tokenize(lb));
        if (j >= 0.85) return 0.0;

        // numeric mismatch -> strong contradiction (when some lexical overlap exists)
        double num = numericMismatchScore(la, lb);
        if (num > 0.0 && j >= 0.20) return clamp(0.55 + num * 0.45, 0.0, 1.0);

        // negation vs positive with overlap -> moderate contradiction
        boolean na = NEGATION.matcher(la).find();
        boolean nb = NEGATION.matcher(lb).find();
        boolean pa = POSITIVE.matcher(la).find();
        boolean pb = POSITIVE.matcher(lb).find();
        if (j >= 0.25 && ((na && pb) || (nb && pa) || (na && !nb && pa != pb) || (nb && !na && pa != pb))) {
            return 0.65;
        }

        // weak default
        return j < 0.10 ? 0.05 : 0.15;
    }

    private static double numericMismatchScore(String a, String b) {
        double[] na = extractNumbers(a);
        double[] nb = extractNumbers(b);
        if (na.length == 0 || nb.length == 0) return 0.0;

        // if any number matches -> reduce contradiction
        for (double x : na) {
            for (double y : nb) {
                if (Math.abs(x - y) <= 1e-9) return 0.0;
            }
        }
        // mismatch present
        return 1.0;
    }

    private static double[] extractNumbers(String s) {
        if (s == null) return new double[0];
        Matcher m = NUMBERS.matcher(s);
        double[] tmp = new double[8];
        int n = 0;
        while (m.find()) {
            if (n == tmp.length) {
                double[] grow = new double[tmp.length * 2];
                System.arraycopy(tmp, 0, grow, 0, tmp.length);
                tmp = grow;
            }
            try {
                tmp[n++] = Double.parseDouble(m.group(1));
            } catch (Exception ignore) { }
        }
        if (n == 0) return new double[0];
        double[] out = new double[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String[] tokenize(String s) {
        if (s == null || s.isBlank()) return new String[0];
        return s.replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd} ]", " ")
                .trim()
                .split("\\s+");
    }

    private static double jaccard(String[] a, String[] b) {
        if (a.length == 0 || b.length == 0) return 0.0;
        java.util.Set<String> sa = new java.util.HashSet<>();
        java.util.Set<String> sb = new java.util.HashSet<>();
        for (String x : a) if (x != null && !x.isBlank()) sa.add(x);
        for (String x : b) if (x != null && !x.isBlank()) sb.add(x);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        int uni = sa.size() + sb.size() - inter;
        return uni <= 0 ? 0.0 : (double) inter / (double) uni;
    }

    private static long symmetricKey(String a, String b) {
        if (a.compareTo(b) <= 0) {
            return (((long) a.hashCode()) << 32) ^ (b.hashCode() & 0xffffffffL);
        }
        return (((long) b.hashCode()) << 32) ^ (a.hashCode() & 0xffffffffL);
    }

    private static double parseNumber0to1(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim();
        if (t.isEmpty()) return Double.NaN;
        Matcher m = FIRST_NUMBER.matcher(t);
        if (!m.find()) return Double.NaN;
        try {
            return clamp(Double.parseDouble(m.group(1)), 0.0, 1.0);
        } catch (Exception ignore) {
            return Double.NaN;
        }
    }

    private static String snippet(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
