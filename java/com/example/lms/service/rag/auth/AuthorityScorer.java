// src/main/java/com/example/lms/service/rag/auth/AuthorityScorer.java
package com.example.lms.service.rag.auth;

import com.example.lms.domain.enums.RerankSourceCredibility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * URL 신뢰도(Authority)를 분류/감쇠 계수로 제공하는 서비스.
 *
 * <p>변경점(부여된 사항):
 * - (하위호환) {@link #weightFor(String)} 는 등급·감쇠 매핑으로 위임(@Deprecated).
 * - {@link #getSourceCredibility(String)} : URL → {@link RerankSourceCredibility} 등급 분류.
 * - {@link #decayFor(RerankSourceCredibility)} : 등급별 지수 감쇠 계수(곱).
 *
 * <p>기본 동작:
 * 1) application.properties 의 search.authority.weights.* 를 먼저 탐색(선택).
 *    - 값 범위 [0,1]을 등급으로 매핑: >=0.95 OFFICIAL, >=0.75 TRUSTED, >=0.50 COMMUNITY, 그 외 UNVERIFIED
 * 2) 설정 매칭이 없으면 내장 휴리스틱으로 분류(정부/교육/벤더/문서, 메이저 미디어/백과, 커뮤니티/블로그 등).
 */
@Slf4j
@Component
public class AuthorityScorer {

    /**
     * application.properties에서 로드된 도메인별 가중치 테이블.
     * 키는 소문자 도메인, 값은 0.0 ~ 1.0 사이의 가중치입니다.
     * (예: search.authority.weights.official=apache.org:1.0,oracle.com:0.98)
     */
    private final Map<String, Double> table;

    /**
     * 구성 값 병합:
     * legacyCsv   예) "foo.com:0.7,bar.com:0.9"
     * officialCsv 예) "apache.org:1.0,oracle.com:0.98"
     * wikiCsv     예) "wikipedia.org:0.92,britannica.com:0.93"
     * communityCsv예) "github.com:0.6,stackoverflow.com:0.65"
     * blogCsv     예) "medium.com:0.55,hashnode.com:0.55"
     */
    public AuthorityScorer(
            @Value("${search.authority.weights:}") String legacyCsv,
            @Value("${search.authority.weights.official:}") String officialCsv,
            @Value("${search.authority.weights.wiki:}") String wikiCsv,
            @Value("${search.authority.weights.community:}") String communityCsv,
            @Value("${search.authority.weights.blog:}") String blogCsv
    ) {
        LinkedHashMap<String, Double> merged = new LinkedHashMap<>();
        merged.putAll(parse(officialCsv));
        merged.putAll(parse(wikiCsv));
        merged.putAll(parse(communityCsv));
        merged.putAll(parse(blogCsv));

        // 새 설정이 비어있으면 레거시 사용
        if (merged.isEmpty()) {
            merged.putAll(parse(legacyCsv));
        }

        this.table = Collections.unmodifiableMap(merged);

        if (table.isEmpty()) {
            log.info("[AuthorityScorer] No explicit weights loaded. Using heuristic-only classification.");
        } else {
            log.info("[AuthorityScorer] Loaded {} domain weight entries.", table.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** (하위호환) weightFor는 등급·감쇠 매핑으로 위임한다. */
    @Deprecated
    public double weightFor(String url) {
        return decayFor(getSourceCredibility(url));
    }

    /** URL을 신뢰도 등급으로 분류한다. */
    public RerankSourceCredibility getSourceCredibility(String url) {
        String host = host(url);
        if (host == null || host.isBlank()) {
            return RerankSourceCredibility.UNVERIFIED;
        }
        String h = host.toLowerCase(Locale.ROOT);

        // 1) 설정 테이블 매칭 → 수치 → 등급
        Double configured = bestMatchingWeight(h, table);
        if (configured != null) {
            return mapWeightToCredibility(configured);
        }

        // 2) 내장 휴리스틱

        // 국내 정부/교육 도메인은 최상위 OFFICIAL로 분류 (보호 차원에서 강조)
        if (h.endsWith(".go.kr") || h.endsWith(".ac.kr")) {
            return RerankSourceCredibility.OFFICIAL;
        }

        // 국내 주요 언론사 및 대형 포털 사이트는 TRUSTED 등급으로 상향
        if (h.endsWith("hani.co.kr") || h.endsWith("chosun.com") || h.endsWith("joongang.co.kr")
                || h.endsWith("donga.com") || h.endsWith("naver.com") || h.endsWith("daum.net")
                || h.endsWith("kbs.co.kr") || h.endsWith("mbc.co.kr") || h.endsWith("sbs.co.kr")
                || h.endsWith("news1.kr") || h.endsWith("newsis.com")) {
            return RerankSourceCredibility.TRUSTED;
        }

        // 블로그 도메인과 개인 포털 블로그는 UNVERIFIED로 하향 (신뢰도 낮음)
        if (h.contains("blog.") || h.contains("blog.naver.com") || h.contains("tistory.com")
                || h.contains("velog.io")) {
            return RerankSourceCredibility.UNVERIFIED;
        }
        // OFFICIAL: 정부/교육/공식 문서/벤더/개발자 문서
        if (isGovOrEdu(h)
                || h.endsWith("apache.org") || h.endsWith("oracle.com")
                || h.endsWith("openai.com") || h.endsWith("google.com")
                || h.endsWith("spring.io")  || h.endsWith("microsoft.com")
                || h.endsWith("apple.com")
                || h.startsWith("developer.") || h.contains("docs.")
        ) {
            return RerankSourceCredibility.OFFICIAL;
        }

        // TRUSTED: 주요 매체/백과
        if (h.endsWith("reuters.com") || h.endsWith("bbc.com")
                || h.endsWith("bloomberg.com") || h.endsWith("nytimes.com")
                || h.endsWith("wsj.com") || h.endsWith("britannica.com")
                || h.endsWith("wikipedia.org")
        ) {
            return RerankSourceCredibility.TRUSTED;
        }

        // 게임 벤더 도메인(hoyoverse/hoyolab)은 더 이상 특별히 우대하지 않는다.
        // 교육 도메인(HRD)과 정부/교육 도메인은 아래 isGovOrEdu()에서 처리된다.
        // 이전에는 호요버스(hoyoverse) 관련 도메인을 OFFICIAL로 분류했지만,
        // 특정 도메인에 편향된 가중치를 방지하기 위해 제거하였다.

        // COMMUNITY: 개발자/커뮤니티/블로그 플랫폼
        if (h.endsWith("github.com") || h.endsWith("stackoverflow.com")
                || h.endsWith("reddit.com") || h.endsWith("medium.com")
                || h.endsWith("hashnode.com")
        ) {
            return RerankSourceCredibility.COMMUNITY;
        }

        // 이전에는 fandom.com과 namu.wiki를 COMMUNITY로 분류하였으나
        // 교육/일반 도메인에 대한 검색 결과를 오염시키는 문제가 있어 UNVERIFIED로 분류한다.

        // 그 외
        return RerankSourceCredibility.UNVERIFIED;
    }

    /** 등급별 지수 감쇠 상수(OFFICIAL=1.0 … UNVERIFIED=0.25). */
    public double decayFor(RerankSourceCredibility credibility) {
        if (credibility == null) return 0.25;
        return switch (credibility) {
            case OFFICIAL   -> 1.0;
            case TRUSTED    -> 0.75;
            case COMMUNITY  -> 0.5;
            case UNVERIFIED -> 0.25;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** CSV "domain:weight,domain2:weight" → Map */
    private static Map<String, Double> parse(String csv) {
        if (csv == null || csv.isBlank()) return Map.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains(":"))
                .map(s -> s.split(":", 2))
                .collect(Collectors.toMap(
                        parts -> parts[0].trim().toLowerCase(Locale.ROOT),
                        parts -> {
                            try {
                                return clamp(Double.parseDouble(parts[1].trim()));
                            } catch (Exception e) {
                                return 0.5; // 파싱 실패 시 중립
                            }
                        },
                        (oldV, newV) -> newV,
                        LinkedHashMap::new
                ));
    }

    /** 호스트가 정부/교육 기관인지 */
    private static boolean isGovOrEdu(String host) {
        return host.endsWith(".go.kr")
                || host.endsWith(".ac.kr")
                || host.endsWith(".gov")
                || host.contains(".edu");
    }

    /** URL → host 안전 추출 */
    private static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** [0,1]로 클램프 */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 설정 테이블에서 가장 잘 매칭되는 가중치 선택(가장 높은 값 우선) */
    private static Double bestMatchingWeight(String host, Map<String, Double> table) {
        if (table == null || table.isEmpty()) return null;
        Double best = null;
        for (Map.Entry<String, Double> e : table.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if (host.contains(key)) {
                if (best == null || e.getValue() > best) {
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    /** 수치 가중치 → 등급 임계값 매핑 */
    private static RerankSourceCredibility mapWeightToCredibility(double w) {
        double v = clamp(w);
        if (v >= 0.95) return RerankSourceCredibility.OFFICIAL;
        if (v >= 0.75) return RerankSourceCredibility.TRUSTED;
        if (v >= 0.50) return RerankSourceCredibility.COMMUNITY;
        return RerankSourceCredibility.UNVERIFIED;
    }
}
