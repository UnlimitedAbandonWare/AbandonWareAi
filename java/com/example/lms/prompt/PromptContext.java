// src/main/java/com/example/lms/prompt/PromptContext.java
package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대화 프롬프트에 투입되는 모든 맥락을 중앙에서 운반하는 DTO.
 * - ChatService가 이 객체를 조합해 PromptBuilder/PromptEngine으로 전달
 */
public record PromptContext(
        String userQuery,                     // 현재 사용자 쿼리
        String lastAssistantAnswer,           // 직전 AI 답변 (후속질문 목적어)
        List<Content> web,                    // 라이브/웹/하이브리드 스니펫
        List<Content> rag,                    // 벡터 RAG 문서
        String memory,                        // 장기/세션 메모리
        String history,                       // 최근 대화 히스토리(문자열 조인)
        String domain,                        // 도메인 힌트
        String intent,                        // 의도 (GENERAL/PAIRING/RECOMMENDATION 등)
        String subject,                       // 주제 앵커
        Set<String> protectedTerms,           // 보존해야 할 엔티티 문자열
        Map<String, Set<String>> interactionRules, // 동적 규칙 맵

        // 출력 정책
        String  verbosityHint,                // brief|standard|deep|ultra
        Integer minWordCount,                 // 최소 단어 수 강제
        List<String> sectionSpec,             // 섹션 헤더 강제
        Integer targetTokenBudgetOut,         // ★ 출력 토큰 예산(선택)

        // 타깃/인용
        String  audience,                     // 대상 독자
        String  citationStyle                 // inline|footnote 등
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String userQuery;
        private String lastAssistantAnswer;
        private List<Content> web;
        private List<Content> rag;
        private String memory;
        private String history;
        private String domain;
        private String intent;
        private String subject;
        private Set<String> protectedTerms;
        private Map<String, Set<String>> interactionRules;
        private String verbosityHint;
        private Integer minWordCount;
        private List<String> sectionSpec;
        private Integer targetTokenBudgetOut;          // ★ 추가
        private String audience;
        private String citationStyle = "inline";

        public Builder userQuery(String v) { this.userQuery = v; return this; }
        public Builder lastAssistantAnswer(String v) { this.lastAssistantAnswer = v; return this; }
        public Builder web(List<Content> v) { this.web = v; return this; }
        public Builder rag(List<Content> v) { this.rag = v; return this; }
        public Builder memory(String v) { this.memory = v; return this; }
        public Builder history(String v) { this.history = v; return this; }
        public Builder domain(String v) { this.domain = v; return this; }
        public Builder intent(String v) { this.intent = v; return this; }
        public Builder subject(String v) { this.subject = v; return this; }
        public Builder protectedTerms(Set<String> v) { this.protectedTerms = v; return this; }
        public Builder interactionRules(Map<String, Set<String>> v) { this.interactionRules = v; return this; }
        public Builder verbosityHint(String v) { this.verbosityHint = v; return this; }
        public Builder minWordCount(Integer v) { this.minWordCount = v; return this; }
        public Builder sectionSpec(List<String> v) { this.sectionSpec = v; return this; }
        public Builder targetTokenBudgetOut(Integer v) { this.targetTokenBudgetOut = v; return this; } // ★ 추가
        public Builder audience(String v) { this.audience = v; return this; }
        public Builder citationStyle(String v) { this.citationStyle = v; return this; }

        public PromptContext build() {
            return new PromptContext(
                    userQuery, lastAssistantAnswer, web, rag, memory, history,
                    domain, intent, subject, protectedTerms, interactionRules,
                    verbosityHint, minWordCount, sectionSpec, targetTokenBudgetOut,
                    audience, citationStyle
            );
        }
    }
}
