package com.example.lms.prompt;

import com.example.lms.service.rag.pre.CognitiveState;
import dev.langchain4j.rag.content.Content;

import java.util.*;

/**
 * 프롬프트 생성 전 과정을 관통하는 '명시적 대화 맥락' DTO (Data Transfer Object).
 * 불변성을 보장하기 위해 Java record로 정의되었으며, 손쉬운 객체 생성을 위해 빌더 패턴을 제공합니다.
 */
public record PromptContext(
        // ───── 1. 대화 상태 ─────
        String userQuery,
        String lastAssistantAnswer,   // 직전 AI 답변 (후속 질문 처리의 핵심 앵커)
        String history,               // 최근 대화 히스토리 (문자열로 조인된 형태)

        // ───── 2. 증거 (컨텍스트) ─────
        List<Content> web,            // 라이브 웹/하이브리드 검색 결과
        List<Content> rag,            // 벡터 DB 검색 결과
        String memory,                // 장기 메모리 (요약 또는 스니펫)

        // ───── 3. 도메인 및 규칙 ─────
        String domain,                // 현재 대화의 도메인 (예: "Genshin Impact")
        String intent,                // 사용자의 의도 (예: "PAIRING", "RECOMMENDATION")
        String subject,               // 대화의 핵심 주제 (예: "Nahida")
        Set<String> protectedTerms,   // 원본 형태를 유지해야 할 고유명사
        Map<String, Set<String>> interactionRules, // 동적으로 적용될 관계 규칙
        CognitiveState cognitiveState,  // ✅ [추가] 인지 상태(추상도/증거/시간/복잡도)

        // ───── 4. 출력 정책 ─────
        String verbosityHint,         // 답변 상세도 힌트 (brief, standard, deep, ultra)
        Integer minWordCount,         // 최소 답변 단어 수 강제
        List<String> sectionSpec,     // 답변에 포함될 섹션 헤더 목록 강제
        Integer targetTokenBudgetOut, // 모델의 출력 토큰 예산
        String audience,              // 답변의 대상 독자층 (예: "초보자")
        String citationStyle          // 출처 표기 스타일 (예: "inline", "footnote")
) {

    /**
     * PromptContext 객체를 생성하기 위한 빌더 인스턴스를 반환합니다.
     * @return 새로운 Builder 인스턴스
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * PromptContext 객체를 안전하고 편리하게 생성하기 위한 Builder 클래스.
     */
    public static final class Builder {
        private String userQuery;
        private String lastAssistantAnswer;
        private String history;
        private List<Content> web = Collections.emptyList();
        private List<Content> rag = Collections.emptyList();
        private String memory;
        private String domain;
        private String intent;
        private String subject;
        private Set<String> protectedTerms = Collections.emptySet();
        private Map<String, Set<String>> interactionRules = Collections.emptyMap();
        private CognitiveState cognitiveState; // ✅ [추가]
        private String verbosityHint = "standard";
        private Integer minWordCount;
        private List<String> sectionSpec = Collections.emptyList();
        private Integer targetTokenBudgetOut;
        private String audience;
        private String citationStyle = "inline";

        public Builder userQuery(String v) { this.userQuery = v; return this; }
        public Builder lastAssistantAnswer(String v) { this.lastAssistantAnswer = v; return this; }
        public Builder history(String v) { this.history = v; return this; }
        public Builder web(List<Content> v) { this.web = (v == null ? Collections.emptyList() : v); return this; }
        public Builder rag(List<Content> v) { this.rag = (v == null ? Collections.emptyList() : v); return this; }
        public Builder memory(String v) { this.memory = v; return this; }
        public Builder domain(String v) { this.domain = v; return this; }
        public Builder intent(String v) { this.intent = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder protectedTerms(Set<String> v) { this.protectedTerms = (v == null ? Collections.emptySet() : v); return this; }
        public Builder interactionRules(Map<String, Set<String>> v) { this.interactionRules = (v == null ? Collections.emptyMap() : v); return this; }
        public Builder cognitiveState(CognitiveState v) { this.cognitiveState = v; return this; } // ✅ [추가]
        public Builder verbosityHint(String v) { this.verbosityHint = (v == null || v.isBlank() ? "standard" : v); return this; }
        public Builder minWordCount(Integer v) { this.minWordCount = v; return this; }
        public Builder sectionSpec(List<String> v) { this.sectionSpec = (v == null ? Collections.emptyList() : v); return this; }
        public Builder targetTokenBudgetOut(Integer v) { this.targetTokenBudgetOut = v; return this; }
        public Builder audience(String v) { this.audience = v; return this; }
        public Builder citationStyle(String v) { this.citationStyle = (v == null || v.isBlank() ? "inline" : v); return this; }

        /**
         * 빌더에 설정된 값들을 바탕으로 최종적인 PromptContext 불변 객체를 생성합니다.
         * @return 생성된 PromptContext 인스턴스
         */
        public PromptContext build() {
            return new PromptContext(
                    userQuery, lastAssistantAnswer, history,
                    web, rag, memory,
                    domain, intent, subject, protectedTerms, interactionRules,
                    cognitiveState, // ✅ [추가]
                    verbosityHint, minWordCount, sectionSpec, targetTokenBudgetOut,
                    audience, citationStyle
            );
        }
    }
}