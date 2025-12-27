package com.example.lms.service.rag.filter;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 노이즈 도메인 키워드를 포함하는 문서를 필터링하는 컴포넌트.
 *
 * <p>엔티티 쿼리에서 동명이의어/동명이인 관련 문서를 제거하는 데 사용됩니다.
 * 예: "을지대 이창화" 의학 검색 시 "경제학자 이창화" 관련 문서 제거.
 */
@Component
public class ContextConsistencyFilter {

    private static final Logger log = LoggerFactory.getLogger(ContextConsistencyFilter.class);

    /**
     * 기대 도메인과 상충되는 노이즈 도메인 키워드를 포함하는 문서를 제거합니다.
     *
     * @param pool           검색 결과 풀
     * @param expectedDomain 기대 도메인 (optional, 로깅 용도)
     * @param noiseDomains   제외할 도메인 키워드 리스트
     * @return 필터링된 문서 리스트
     */
    public List<Doc> filter(List<Doc> pool, String expectedDomain, List<String> noiseDomains) {
        if (pool == null || pool.isEmpty() || noiseDomains == null || noiseDomains.isEmpty()) {
            return pool;
        }

        final List<String> loweredNoise = noiseDomains.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.toLowerCase(Locale.ROOT).trim())
                .collect(Collectors.toList());

        if (loweredNoise.isEmpty()) {
            return pool;
        }

        return pool.stream()
                .filter(doc -> {
                    String title = doc.title != null ? doc.title : "";
                    String snippet = doc.snippet != null ? doc.snippet : "";
                    String content = (title + " " + snippet).toLowerCase(Locale.ROOT);

                    boolean isNoise = loweredNoise.stream().anyMatch(content::contains);
                    if (isNoise) {
                        log.debug("[ConsistencyFilter] Dropping noisy doc (expectedDomain={}, noise={}): title='{}'",
                                expectedDomain, loweredNoise, title);
                    }
                    return !isNoise;
                })
                .collect(Collectors.toList());
    }
}
