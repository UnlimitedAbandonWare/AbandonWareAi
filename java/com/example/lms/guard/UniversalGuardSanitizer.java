// src/main/java/com/example/lms/guard/UniversalGuardSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * UniversalGuardSanitizer
 *
 * <p>도메인/속성 Lexicon(UniversalContextLexicon)을 기반으로
 * 답변에 섞인 "도메인 혼선" 신호를 감시하는 가벼운 가드입니다.
 *
 * <p>예시:
 * <ul>
 *   <li>Java 질문(TECH_JAVA)인데 답변에 pandas/pip 등이 들어간 경우</li>
 *   <li>원신 캐릭터 추천 질문인데 "국비지원 학원" 광고 문구가 끼어드는 경우</li>
 * </ul>
 *
 * <p>현재 버전은 <b>비파괴(non-destructive)</b> 모드로 동작하며,
 * 문제가 감지되면 로그만 남기도록 설계되었습니다.
 * 추후 정책에 따라 재생성 트리거나 경고 주석 추가로 확장할 수 있습니다.
 */
@Component
public class UniversalGuardSanitizer implements AnswerSanitizer {

    private static final Logger log = LoggerFactory.getLogger(UniversalGuardSanitizer.class);

    private final UniversalContextLexicon lexicon;

    public UniversalGuardSanitizer(UniversalContextLexicon lexicon) {
        this.lexicon = lexicon;
    }

    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null) {
            return null;
        }
        if (ctx == null || ctx.userQuery() == null || ctx.userQuery().isBlank()) {
            return answer;
        }

        String query = ctx.userQuery();
        UniversalContextLexicon.Policy policy = lexicon.policyForQuery(query);

        if (policy == null || policy.discouraged().isEmpty()) {
            return answer;
        }

        String lowerAnswer = answer.toLowerCase(Locale.ROOT);

        for (String badToken : policy.discouraged()) {
            if (badToken == null || badToken.isBlank()) {
                continue;
            }
            if (lowerAnswer.contains(badToken.toLowerCase(Locale.ROOT))) {
                log.warn("[Guard] Domain confusion detected. query='{}', badToken='{}'", query, badToken);
                // 현재는 비파괴 모드: 답변은 그대로 반환
                // 필요 시 여기에 경고 문구 추가 / 재생성 트리거 등을 연결할 수 있음.
                break;
            }
        }

        return answer;
    }
}
