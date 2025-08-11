package com.example.lms.service.rag.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * URL → Authority weight(0.0~1.0) 계산기.
 * 1) application.properties 의 search.authority.weights (domain:weight, comma-separated)
 * 2) 휴리스틱: *.go.kr / *.ac.kr / *.gov / *.edu 등은 상향, 팬커뮤니티/커뮤는 하향.
 */
@Slf4j
@Component
public class AuthorityScorer {

    private final Map<String, Double> table;

    public AuthorityScorer(
            @Value("${search.authority.weights:}") String legacyCsv,
            @Value("${search.authority.weights.official:}") String officialCsv,
            @Value("${search.authority.weights.wiki:}") String wikiCsv,
            @Value("${search.authority.weights.community:}") String communityCsv,
            @Value("${search.authority.weights.blog:}") String blogCsv) {
        LinkedHashMap<String, Double> merged = new LinkedHashMap<>();
        merged.putAll(parse(officialCsv));
        merged.putAll(parse(wikiCsv));
        merged.putAll(parse(communityCsv));
        merged.putAll(parse(blogCsv));
        if (merged.isEmpty()) merged.putAll(parse(legacyCsv)); // fallback
        this.table = java.util.Collections.unmodifiableMap(merged);
        if (table.isEmpty()) {
            log.info("[AuthorityScorer] weights empty → using heuristics only");
        } else {
            log.info("[AuthorityScorer] loaded {} domain weights", table.size());
        }
    }

    private static Map<String, Double> parse(String csv) {
        if (csv == null || csv.isBlank()) return Map.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains(":"))
                .map(s -> s.split(":", 2))
                .collect(Collectors.toMap(
                        p -> p[0].trim().toLowerCase(Locale.ROOT),
                        p -> {
                            try { return clamp(Double.parseDouble(p[1].trim())); }
                            catch (Exception e) { return 0.5; }
                        },
                        (a, b) -> b,
                        LinkedHashMap::new
                ));
    }

    public double weightFor(String url) {
        String host = host(url);
        if (host == null) return 0.5;
        String h = host.toLowerCase(Locale.ROOT);

        // 1) 명시 테이블 우선(서브도메인은 contains 매칭)
        for (Map.Entry<String, Double> e : table.entrySet()) {
            if (h.contains(e.getKey())) return clamp(e.getValue());
        }

        // 2) 휴리스틱
        if (isGovOrEdu(h)) return 0.95;
        if (h.contains("hoyoverse.com") || h.contains("hoyolab.com")) return 1.0;
        if (h.contains("wikipedia.org")) return 0.90;
        if (h.contains("namu.wiki")) return 0.75;
        if (h.contains("blog.naver.com")) return 0.55;
        if (h.contains("fandom.com")) return 0.50;
        if (h.contains("arca.live")) return 0.20;

        // 기본값(중립)
        return 0.50;
    }

    private static boolean isGovOrEdu(String host) {
        return host.endsWith(".go.kr")
                || host.endsWith(".ac.kr")
                || host.endsWith(".gov")
                || host.contains(".edu");
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try { return URI.create(url).getHost(); }
        catch (Exception e) { return null; }
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
