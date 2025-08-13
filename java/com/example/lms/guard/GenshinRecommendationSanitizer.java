// src/main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;

@Component
public class GenshinRecommendationSanitizer implements AnswerSanitizer {
    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null) return "";
        if (!"RECOMMENDATION".equalsIgnoreCase(ctx.intent())) return answer;
        if (!"GENSHIN".equalsIgnoreCase(ctx.domain())) return answer;

        // 금지 원소가 명시된 경우, 대표 PYRO 캐릭터 출현 차단
        boolean pyroDiscouraged = ctx.discouragedElements() != null &&
                ctx.discouragedElements().stream().anyMatch("PYRO"::equalsIgnoreCase);
        if (!pyroDiscouraged) return answer;

        String low = answer.toLowerCase();
        if (low.matches(".*(다이루크|호두|향릉|신염|연비|데히야).*")) {
            return "정보 없음"; // 필요 시 재시도 루트 연결 가능
        }
        return answer;
    }
}
