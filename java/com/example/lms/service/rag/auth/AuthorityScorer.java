

// src/main/java/com/example/lms/service/rag/auth/AuthorityScorer.java
        package com.example.lms.service.rag.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * URL의 신뢰도(Authority) 점수(0.0 ~ 1.0)를 계산합니다.
 * 점수 계산 우선순위:
 * 1. application.properties에 명시된 도메인별 가중치 (search.authority.weights.*)
 * 2. 내장된 휴리스틱 규칙 (예: *.go.kr, *.ac.kr 등 공공/교육기관은 상향, 특정 커뮤니티는 하향)
 */
@Slf4j
@Component
public class AuthorityScorer {

    /**
     * application.properties에서 로드된 도메인별 가중치 테이블.
     * 키는 소문자 도메인, 값은 0.0 ~ 1.0 사이의 가중치입니다.
     */
    private final Map<String, Double> table;

    /**
     * 생성자에서 application.properties의 여러 가중치 설정을 주입받아 하나의 맵으로 통합합니다.
     * @param legacyCsv 레거시 설정 (하위 호환용)
     * @param officialCsv 공식 사이트 가중치
     * @param wikiCsv 위키 사이트 가중치
     * @param communityCsv 커뮤니티 사이트 가중치
     * @param blogCsv 블로그 사이트 가중치
     */
    public AuthorityScorer(
            @Value("${search.authority.weights:}") String legacyCsv,
            @Value("${search.authority.weights.official:}") String officialCsv,
            @Value("${search.authority.weights.wiki:}") String wikiCsv,
            @Value("${search.authority.weights.community:}") String communityCsv,
            @Value("${search.authority.weights.blog:}") String blogCsv) {

        // 순서를 보장하는 LinkedHashMap을 사용하여 가중치 설정 병합
        LinkedHashMap<String, Double> merged = new LinkedHashMap<>();
        merged.putAll(parse(officialCsv));
        merged.putAll(parse(wikiCsv));
        merged.putAll(parse(communityCsv));
        merged.putAll(parse(blogCsv));

        // 만약 새로운 설정값이 비어있다면, 하위 호환성을 위해 레거시 설정 사용
        if (merged.isEmpty()) {
            merged.putAll(parse(legacyCsv));
        }

        this.table = java.util.Collections.unmodifiableMap(merged);

        if (table.isEmpty()) {
            log.info("[AuthorityScorer] 로드된 가중치 없음. 휴리스틱 규칙만 사용합니다.");
        } else {
            log.info("[AuthorityScorer] {}개의 도메인 가중치를 로드했습니다.", table.size());
        }
    }

    /**
     * "domain:weight,domain2:weight" 형식의 CSV 문자열을 파싱하여 Map으로 변환합니다.
     * @param csv 파싱할 CSV 문자열
     * @return 파싱된 도메인-가중치 맵
     */
    private static Map<String, Double> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains(":"))
                .map(s -> s.split(":", 2))
                .collect(Collectors.toMap(
                        parts -> parts[0].trim().toLowerCase(Locale.ROOT), // 도메인은 소문자로 정규화
                        parts -> {
                            try {
                                return clamp(Double.parseDouble(parts[1].trim())); // 가중치를 0.0-1.0으로 제한
                            } catch (Exception e) {
                                return 0.5; // 파싱 실패 시 기본값
                            }
                        },
                        (existingValue, newValue) -> newValue, // 중복 키 발생 시 새 값으로 덮어쓰기
                        LinkedHashMap::new
                ));
    }

    /**
     * 주어진 URL에 대한 신뢰도 점수를 계산합니다.
     * @param url 점수를 계산할 URL 문자열
     * @return 0.0에서 1.0 사이의 신뢰도 점수
     */
    public double weightFor(String url) {
        String host = host(url);
        if (host == null) {
            return 0.5; // 호스트를 추출할 수 없으면 중립 점수 반환
        }
        String h = host.toLowerCase(Locale.ROOT);

        // 1. 설정 파일에 명시된 가중치 테이블을 우선적으로 확인 (서브도메인 포함 매칭)
        for (Map.Entry<String, Double> entry : table.entrySet()) {
            if (h.contains(entry.getKey())) {
                return clamp(entry.getValue());
            }
        }

        // 2. 테이블에 일치하는 항목이 없으면, 내장된 휴리스틱 규칙 적용
        if (isGovOrEdu(h)) return 0.95;
        if (h.contains("hoyoverse.com") || h.contains("hoyolab.com")) return 1.0;
        if (h.contains("wikipedia.org")) return 0.92;
        if (h.contains("namu.wiki")) return 0.85; // 게임/서브컬처 주제에 대한 가중치 상향
        if (h.contains("blog.naver.com")) return 0.60;
        if (h.contains("fandom.com")) return 0.50;
        if (h.contains("arca.live")) return 0.20;

        // 3. 위 모든 규칙에 해당하지 않으면 기본값(중립) 반환
        return 0.50;
    }

    /**
     * 호스트가 정부기관(.gov, .go.kr) 또는 교육기관(.edu, .ac.kr)인지 확인합니다.
     */
    private static boolean isGovOrEdu(String host) {
        return host.endsWith(".go.kr")
                || host.endsWith(".ac.kr")
                || host.endsWith(".gov")
                || host.contains(".edu");
    }

    /**
     * URL 문자열에서 호스트(도메인) 부분을 안전하게 추출합니다.
     */
    private static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            // 잘못된 형식의 URL일 경우 null 반환
            return null;
        }
    }

    /**
     * 주어진 값을 0.0과 1.0 사이로 제한합니다.
     */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}