// src/main/java/com/example/lms/prompt/PromptContext.java
package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 프롬프트 빌드를 위한 컨텍스트 컨테이너.
 * - web: 웹 검색 증거 목록
 * - rag: 벡터/RAG 증거 목록
 * - memory: 세션/장기 메모리 요약
 * - history: 대화 이력(필요 시 축약본)
 * - domain: 도메인 힌트(예: "GENSHIN")
 * - intent: 의도(예: "PAIRING", "GENERAL")
 * - interactionRules: 허용/비선호 등 정책 맵 (key: "allowed"/"discouraged"...)
 * - verbosityHint: brief|standard|deep|ultra
 * - minWordCount: 최소 단어 수 강제
 * - targetTokenBudgetOut: 출력 토큰 예산
 * - sectionSpec: 필수 섹션 헤더(순서 보장용)
 * - audience: dev|pm|enduser 등 대상층
 * - citationStyle: 인용 스타일(예: "inline")
 *
 * 실제 널/기본값 정규화는 PromptBuilder.build(PromptContext)에서 수행한다.
 */
@Builder
public record PromptContext(
        List<Content> web,
        List<Content> rag,
        String memory,
        String history,
        String domain,
        String intent,
        Map<String, Set<String>> interactionRules,
        // ▼ 출력 정책(Verbosity 신호 전파)
        String  verbosityHint,         // brief|standard|deep|ultra
        Integer minWordCount,          // 최소 단어수
        Integer targetTokenBudgetOut,  // 출력 토큰 버짓
        List<String> sectionSpec,      // 필수 섹션 헤더
        String  audience,              // dev|pm|enduser
        String  citationStyle,         // 예: "inline"
        // ▼ 앵커 보존/보호
        String  subject,
        Set<String> protectedTerms,
        List<String> lastSources
) {}


