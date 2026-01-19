package com.example.lms.service.fallback;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.example.lms.nlp.QueryDomainClassifier;
import com.example.lms.rag.model.QueryDomain;

/**
 * 간단 휴리스틱:
 * - 질의에서 게임/도메인(예: 원신)과 비도메인 문제어(예: 에스코피에)를 함께 감지
 * - 상황별 대안 후보를 몇 개 제안
 *
 * <p>LLM/RAG 기반 처리가 우선이며, 이 클래스는
 * 아주 명백한 오용 패턴이 보일 때만 보조 신호를 제공합니다.</p>
 */
@Deprecated
public final class FallbackHeuristics {

    // Query 도메인을 판별하기 위한 경량 분류기 (LLM 이전 단계에서 동작)
    private static final QueryDomainClassifier DOMAIN_CLASSIFIER = new QueryDomainClassifier();


    // 게임 도메인 마커 (필요 시 확장 가능)
    private static final Set<String> GENSHIN_MARKERS = Set.of(
            "원신", "genshin", "genshin impact"
    );
    private static final Set<String> STAR_RAIL_MARKERS = Set.of(
            "스타레일", "스타 레일", "star rail", "honkai star rail"
    );

    // 비도메인(게임 외) 문제어 예시 - 필요 시 확장
    private static final Set<String> NON_GAME_TERMS = Set.of(
            "에스코피에", "에스코피", "escoffier", "auguste escoffier"
    );

    private FallbackHeuristics() {
    }

    /**
     * 게임 질의와 비게임 고유명사가 함께 등장하는 "매우 확실한" 경우에만
     * Detection 을 반환합니다.
     *
     * <p>그 외 애매한 경우에는 null 을 반환하여, RAG/LLM 파이프라인이
     * 자유롭게 판단하도록 둡니다.</p>
     *
     * <p>주의: 이 메서드는 <strong>차단용 가드레일이 아니라</strong>
     * SmartFallbackService 에서 사용되는 보조 신호입니다.
     * 즉, LLM/RAG 최종 답변을 덮어쓰지 않고, 추가 안내 문단을 붙이는 용도로만 쓰입니다.</p>
     */

    public static Detection detect(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String normalized = query.toLowerCase(Locale.ROOT);

        // 1) QueryDomainClassifier를 사용하여 우선적으로 도메인 범주를 판단
        QueryDomain domainType;
        try {
            domainType = DOMAIN_CLASSIFIER.classify(query);
        } catch (Exception e) {
            domainType = QueryDomain.safeDefault();
        }

        // 게임/서브컬처 도메인이 아닌 경우에는 휴리스틱을 적용하지 않는다.
        if (domainType != QueryDomain.GAME && domainType != QueryDomain.SUBCULTURE) {
            return null;
        }

        // 2) 게임 내 세부 도메인(원신 / 스타레일 등) 힌트 감지
        String domain = null;
        if (containsAny(normalized, GENSHIN_MARKERS)) {
            domain = "원신";
        } else if (containsAny(normalized, STAR_RAIL_MARKERS)) {
            domain = "스타레일";
        }

        // 세부 도메인을 특정하지 못한 경우, 상위 도메인명을 사용
        if (domain == null) {
            domain = (domainType == QueryDomain.GAME) ? "게임" : domainType.name();
        }

        // 3) 비도메인(게임 외) 문제어가 함께 등장하는지 확인
        String wrongTerm = null;
        for (String term : NON_GAME_TERMS) {
            String t = term.toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && normalized.contains(t)) {
                wrongTerm = term;
                break;
            }
        }

        if (wrongTerm == null) {
            return null;
        }

        return new Detection(domain, wrongTerm);
    }




    private static boolean containsAny(String text, Set<String> markers) {
        if (text == null || text.isEmpty() || markers == null || markers.isEmpty()) {
            return false;
        }
        for (String m : markers) {
            if (m == null || m.isEmpty()) {
                continue;
            }
            String needle = m.toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
    public static java.util.List<String> suggestAlternatives(String domain, String wrongTerm) {
        if (!StringUtils.hasText(domain) || !StringUtils.hasText(wrongTerm)) {
            return java.util.List.of();
        }
        // TODO: 도메인별 추천 후보를 외부 설정/지식베이스로 이전
        return java.util.List.of();
    }

    public record Detection(String domain, String wrongTerm) {
    }
}