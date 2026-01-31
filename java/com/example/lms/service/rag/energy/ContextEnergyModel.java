package com.example.lms.service.rag.energy;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;




/** 컨텍스트 집합의 총 에너지를 최소화하도록 선택 */
@Component
public class ContextEnergyModel {

    private final AuthorityScorer authorityScorer;
    private final ContradictionScorer contradictionScorer;
    private final HyperparameterService hp;

    public ContextEnergyModel(AuthorityScorer authorityScorer,
                              ContradictionScorer contradictionScorer,
                              HyperparameterService hp) {
        this.authorityScorer = authorityScorer;
        this.contradictionScorer = contradictionScorer;
        this.hp = hp;
    }

    public List<Content> selectByEnergy(String query, List<Content> candidates, int cap) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        cap = Math.max(1, cap);

        // 초기: 관련도 근사 점수 내림차순 (토큰 Jaccard)
        List<Scored> scored = candidates.stream()
                .map(c -> new Scored(c, relatedness(query, textOf(c))))
                .sorted((a,b) -> Double.compare(b.rel, a.rel))
                .collect(Collectors.toList());

        // Greedy Forward Selection: 에너지 감소시키는 항목만 채택
        List<Scored> picked = new ArrayList<>();
        double currentE = energy(query, picked);
        for (Scored s : scored) {
            if (picked.size() >= cap) break;
            picked.add(s);
            double e2 = energy(query, picked);
            if (e2 <= currentE || picked.size() == 1) { // 첫 개는 무조건 수용
                currentE = e2;
            } else {
                picked.remove(picked.size()-1);
            }
        }

        return picked.stream().map(sc -> sc.c).toList();
    }

    // -------------------- Energy --------------------
    private double energy(String query, List<Scored> subset) {
        if (subset.isEmpty()) return 1e9;

        double wRel  = hp.getDouble("energy.w.rel", 0.6);
        double wAuth = hp.getDouble("energy.w.auth", 0.2);
        double wRec  = hp.getDouble("energy.w.rec", 0.1);
        double wRed  = hp.getDouble("energy.w.red", 0.6);     // redundancy penalty
        double wCtr  = hp.getDouble("energy.w.ctr", 0.8);     // contradiction penalty

        double sum = 0.0;
        // 1) 개별 항: 낮을수록 좋게 (- 이득)
        for (Scored s : subset) {
            String t = textOf(s.c);
            double rel  = s.rel;
            double auth = authorityScorer != null ? authorityScorer.weightFor(extractUrl(t)) : 0.5;
            double rec  = recencyBonus(t);
            sum += -(wRel*rel + wAuth*auth + wRec*rec);
        }
        // 2) 쌍 항: 중복/모순 페널티 (+ 비용)
        for (int i=0;i<subset.size();i++) {
            for (int j=i+1;j<subset.size();j++) {
                String a = textOf(subset.get(i).c);
                String b = textOf(subset.get(j).c);
                sum += wRed * jaccard(tokens(a), tokens(b));
                sum += wCtr * contradictionScorer.score(a, b);
            }
        }
        return sum;
    }

    // -------------------- Helpers --------------------
    private record Scored(Content c, double rel) {}

    private static String textOf(Content c) {
        return Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
    }

    private static Set<String> tokens(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd}]+"," ").trim();
        if (t.isBlank()) return Set.of();
        return Arrays.stream(t.split("\\s+")).filter(w -> w.length()>1).collect(Collectors.toSet());
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        int inter=0;
        for (String x: a) if (b.contains(x)) inter++;
        int union = a.size()+b.size()-inter;
        return union==0 ? 0 : (double) inter/union;
    }

    private static final Pattern YEAR = Pattern.compile("(20\\d{2}|19\\d{2})");

    private static double recencyBonus(String t) {
        if (t == null) return 0.0;
        var m = YEAR.matcher(t);
        int max = -1;
        while (m.find()) {
            try {
                max = Math.max(max, Integer.parseInt(m.group()));
            } catch (Exception ignore) {
            }
        }
        if (max < 0) return 0.0;
        int year = java.time.Year.now().getValue();
        int age = Math.max(0, year - max);
        // 최신(최근) 연도일수록 가중치를 강화한다. 기존 지수 감쇠 결과에 보너스 계수(1.5)를 곱한다.
        double base = Math.exp(-age / 3.0);
        return base * 1.5;
    }

    private static String extractUrl(String text) {
        if (text == null) return null;
        int a = text.indexOf("http");
        if (a < 0) return null;
        int sp = text.indexOf(' ', a);
        return sp > a ? text.substring(a, sp) : text.substring(a);
    }

    private static double relatedness(String q, String d) {
        if (q == null || d == null) return 0.0;
        Set<String> tq = tokens(q); Set<String> td = tokens(d);
        return jaccard(tq, td);
    }
}