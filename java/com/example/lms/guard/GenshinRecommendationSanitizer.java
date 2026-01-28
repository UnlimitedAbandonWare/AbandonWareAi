// src/main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.service.rag.guard.UniversalDomainSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * @deprecated Use {@link UniversalDomainSanitizer} instead.
 *
 * <p>과거 Genshin 전용 추천 가드. 지금은
 * {@link UniversalDomainSanitizer} 에 위임하는 얇은 호환 계층입니다.
 * 새로운 코드에서는 UniversalDomainSanitizer 또는 GameRecommendationSanitizer 를
 * 직접 사용하는 것을 권장합니다.</p>
 */
@Component
@Deprecated
@RequiredArgsConstructor
public class GenshinRecommendationSanitizer implements AnswerSanitizer {

    private final UniversalDomainSanitizer delegate;

    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }

        // 도메인이 비어 있으면 기본으로 "genshin"을 사용
        String domain = null;
        if (ctx != null) {
            domain = ctx.domain();
            if ((domain == null || domain.isBlank()) && ctx.queryDomain() == QueryDomain.GAME) {
                domain = "genshin";
            }
        } else {
            domain = "genshin";
        }

        // UniversalDomainSanitizer 는 PromptContext 기반 구현이므로
        // 실제 도메인 정보는 ctx 내부에 그대로 두고, sanitize 를 위임한다.
        return delegate.sanitize(answer, ctx);
    }

    /**
     * 과거 구현에서 사용하던 위험 패턴 탐지 로직.
     * UniversalDomainSanitizer 기반 규칙으로 점진 이관하기 위해
     * 유틸 메서드 형태로 보존만 한다.
     */
    @SuppressWarnings("unused")
    private boolean containsDangerousPattern(String text) {
        if (text == null) return false;
        return text.contains("계정 거래")
                || text.contains("핵 다운")
                || text.contains("사설 서버")
                || text.matches(".*[0-9]+만원.*과금.*");
    }
}
