// src/main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Component
public class GenshinRecommendationSanitizer implements AnswerSanitizer {
    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null) return "";
        if (!"RECOMMENDATION".equalsIgnoreCase(ctx.intent())) return answer;
        if (!"GENSHIN".equalsIgnoreCase(ctx.domain())) return answer;

        // 금지 원소가 명시된 경우, 대표 PYRO 캐릭터 출현 차단
        // 동적 관계 규칙 중 '금지/회피/약점' 성격 키에서 PYRO가 포함되면 차단
        boolean pyroDiscouraged = false;
        Map<String, Set<String>> rules = ctx.interactionRules();
        if (rules != null && !rules.isEmpty()) {
            for (Map.Entry<String, Set<String>> e : rules.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String ku = k.toUpperCase(Locale.ROOT);
                // 예: RELATIONSHIP_DISCOURAGED_WITH, RELATIONSHIP_AVOID, RELATIONSHIP_WEAK_TO, RELATIONSHIP_COUNTERED_BY ...
                if (ku.contains("DISCOURAGED") || ku.contains("AVOID") || ku.contains("WEAK") || ku.contains("COUNTER")) {
                    Set<String> vals = e.getValue();
                    if (vals != null && vals.stream().anyMatch(v -> "PYRO".equalsIgnoreCase(v))) {
                        pyroDiscouraged = true;
                        break;
                    }
                }
            }
        }
        if (!pyroDiscouraged) return answer;

        String low = answer.toLowerCase();
        if (low.matches(".*(다이루크|호두|향릉|신염|연비|데히야).*")) {
            return "정보 없음"; // 필요 시 재시도 루트 연결 가능
        }
        return answer;
    }
}
