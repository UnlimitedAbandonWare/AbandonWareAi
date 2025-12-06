package com.example.lms.service.rag.overdrive;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.stream.Collectors;




import static com.example.lms.service.rag.QueryUtils.buildQuery;

/** 앵커 중심 점군 압축 → 단계별 K-스케줄 축소 → 최종 정밀 탐침(Quote) */
@Component
public class AngerOverdriveNarrower {

    private final AnalyzeWebSearchRetriever web;
    private final AuthorityScorer authority;

    public AngerOverdriveNarrower(AnalyzeWebSearchRetriever web, AuthorityScorer authority) {
        this.web = web;
        this.authority = authority;
    }

    @Value("${rag.overdrive.k-schedule:48,32,16,8}")
    private String kScheduleCsv;

    @Value("${rag.overdrive.relatedness.start:0.20}")
    private double relStart;
    @Value("${rag.overdrive.relatedness.step:0.10}")
    private double relStep;

    @Value("${rag.overdrive.max-stages:4}")
    private int maxStages;

    public List<Content> narrow(String userQuery, List<Content> current) {
        String anchor = pickAnchor(userQuery);
        if (anchor == null || anchor.isBlank()) return current;

        int[] kSchedule = parseSchedule(kScheduleCsv);
        List<Content> pool = new ArrayList<>(dedup(current));
        double relTh = relStart;

        for (int stage = 0; stage < Math.min(maxStages, kSchedule.length); stage++) {
            int k = kSchedule[stage];

            // 1) 쿼리 세분화 (앵커 우선, 마지막 단계는 정확 구문)
            List<String> queries = (stage == kSchedule.length - 1)
                    ? List.of("\"" + anchor + "\"")
                    : List.of(anchor + " " + userQuery, anchor);

            // 2) 웹 검색 수집 (fail-soft)
            List<Content> fetched = new ArrayList<>();
            for (String q : queries) {
                try { fetched.addAll(web.retrieve(buildQuery(q))); } catch (Exception ignored) {}
            }
            pool.addAll(fetched);

            // 3) 앵커 관련도와 권위 가중으로 점군 압축
            List<Sc> ranked = dedup(pool).stream()
                    .map(c -> new Sc(text(c), c, relatedness(anchor, text(c)) * authWeight(text(c))))
                    .sorted(Comparator.comparingDouble((Sc s) -> -s.score))
                    .collect(Collectors.toList());
        final double relThFinal = relTh;


            // 4) 관련도 임계 ↑ & 상위 K 유지
            List<Content> next = ranked.stream()
                    .filter(s -> s.score >= relThFinal)
                    .limit(k)
                    .map(s -> s.c)
                    .toList();

            if (!next.isEmpty()) pool = new ArrayList<>(next);
            relTh += relStep;
        }
        return pool;
    }

    private static class Sc {
        final String t; final Content c; final double score;
        Sc(String t, Content c, double score) { this.t = t; this.c = c; this.score = score; }
    }

    private static String text(Content c) {
        return c == null || c.textSegment() == null ? null : c.textSegment().text();
    }

    private static Set<Content> dedup(List<Content> list) {
        LinkedHashMap<String, Content> map = new LinkedHashMap<>();
        for (Content c : list) {
            String key = Optional.ofNullable(text(c)).orElse(UUID.randomUUID().toString());
            map.putIfAbsent(key, c);
        }
        return new LinkedHashSet<>(map.values());
    }

    private static String pickAnchor(String q) {
        if (q == null) return null;
        // 가장 긴 한글/영문/숫자 연속 토큰을 앵커 후보로 (간단, 노이즈 적음)
        String best = null;
        for (String tok : q.split("\\s+")) {
            if (tok.length() < 2) continue;
            if (best == null || tok.length() > best.length()) best = tok;
        }
        return best;
    }

    private static double relatedness(String a, String b) {
        if (a == null || b == null) return 0.0;
        Set<String> ta = tokens(a), tb = tokens(b);
        int inter = 0;
        for (String t : ta) if (tb.contains(t)) inter++;
        int uni = ta.size() + tb.size() - inter;
        return uni == 0 ? 0.0 : (inter / (double) uni);
    }

    private static Set<String> tokens(String s) {
        String norm = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        return Arrays.stream(norm.trim().split("\\s+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private double authWeight(String text) {
        String url = extractUrl(text);
        var cred = authority.getSourceCredibility(url);
        return authority.decayFor(cred);
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        int i = text.indexOf("http");
        if (i < 0) return null;
        int sp = text.indexOf(' ', i);
        return sp > i ? text.substring(i, sp) : text.substring(i);
    }

    private static int[] parseSchedule(String csv) {
        try {
            return Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .mapToInt(Integer::parseInt).toArray();
        } catch (Exception e) {
            return new int[]{48, 32, 16, 8};
        }
    }
}