// src/main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java
package com.example.lms.guard;
import com.example.lms.rag.model.QueryDomain;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;

/**
 * 과거 Genshin 전용 추천 가드.
 *
 * <p>지금은 {@link UniversalGuardSanitizer} 에 위임하는 얇은 호환 계층입니다.
 * 새로운 코드에서는 UniversalGuardSanitizer 또는 GameRecommendationSanitizer 를
 * 직접 사용하는 것을 권장합니다.
 */
@Component
@Deprecated // UniversalGuardSanitizer 사용 권장
public class GenshinRecommendationSanitizer implements AnswerSanitizer {

    private final UniversalGuardSanitizer delegate;

    public GenshinRecommendationSanitizer(UniversalGuardSanitizer delegate) {
        this.delegate = delegate;
    }

    @Override
    public String sanitize(String answer, PromptContext ctx) {
        // 단순 위임 - 기존 빈 이름을 사용하는 구성에서도
        // 범용 Guard 로직이 실행되도록 한다.
        return delegate.sanitize(answer, ctx);
    }

    private boolean containsDangerousPattern(String text) {
        if (text == null) return false;
        return text.contains("계정 거래")
                || text.contains("핵 다운")
                || text.contains("사설 서버")
                || text.matches(".*[0-9]+만원.*과금.*");
    }

}
