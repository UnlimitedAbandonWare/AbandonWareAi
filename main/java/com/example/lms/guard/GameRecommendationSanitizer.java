// src/main/java/com/example/lms/guard/GameRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 게임/원신 도메인용 기본 AnswerSanitizer.
 *
 * <p>예전에는 Genshin 전용 원소(PYRO 등)에 대한 하드코딩된 규칙을 포함했지만,
 * 이제는 {@link UniversalGuardSanitizer} 에 위임하는 범용 게임 가드로 동작합니다.
 *
 * <p>기존 구성의 @Primary AnswerSanitizer 역할을 유지하여,
 * 명시적인 빈 교체 없이도 범용 Guard 로직을 사용할 수 있습니다.
 */
@Primary
@Component
public class GameRecommendationSanitizer implements AnswerSanitizer {

    private final UniversalGuardSanitizer delegate;

    public GameRecommendationSanitizer(UniversalGuardSanitizer delegate) {
        this.delegate = delegate;
    }

    @Override
    public String sanitize(String answer, PromptContext ctx) {
        // 현재는 UniversalGuardSanitizer 로만 위임합니다.
        // 필요 시 게임 전용 추가 규칙을 이 메서드에서 후처리로 덧붙이세요.
        return delegate.sanitize(answer, ctx);
    }
}
