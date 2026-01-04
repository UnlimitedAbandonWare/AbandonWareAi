
        package com.example.lms.prompt;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;




/**
 * PromptEngine 인터페이스의 기본 구현체.
 * 두 가지 방식의 프롬프트 생성을 모두 지원합니다. (메서드 오버로딩)
 * 1. 기본 RAG (Question + Docs)
 * 2. 동적 규칙 기반 (PromptContext)
 */
@Component("defaultPromptEngine")
@Primary
public class DefaultPromptEngine implements PromptEngine {

    // [기존] 기본 RAG 방식에서 사용하던 템플릿
    private static final String TEMPLATE = """
            ### CONTEXT (ranked, most relevant first)
            %s

            ### QUESTION
            %s

            ### INSTRUCTIONS
            - You are a helpful AI assistant.
            - Answer the QUESTION strictly and solely using the CONTEXT above. Do not invent facts.
            - Earlier context items have higher authority.
            - For pairing/synergy questions: recommend pairs ONLY with explicit synergy cues (e.g., "잘 어울린다", "시너지", "조합"). NEVER recommend pairs based on mere stat comparisons or co-mentions.
            - Answer in Korean.
            - If you can find the source, cite its number like [1], [2].
            - If the context is clearly insufficient, reply: "확실한 정보를 찾지 못했습니다."
            """;

    /**
     * [기존] 질문과 문서 목록을 기반으로 프롬프트를 생성합니다.
     */
    @Override
    public String createPrompt(String question, List<Content> docs) {
        String context = formatDocsAsBulletedList(docs);
        return String.format(TEMPLATE, context, question);
    }

    /**
     * [새로 추가됨] PromptContext 객체를 기반으로 동적 규칙이 포함된 프롬프트를 생성합니다.
     */
    @Override
    public String createPrompt(PromptContext c) {
        // null 체크를 포함하여 스트림을 안전하게 생성합니다.
        String ctx = Stream
                .of(c == null ? List.<Content>of() : c.web(), c == null ? List.<Content>of() : c.rag())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(Object::toString)
                .collect(Collectors.joining("\n\n")); // 문맥 사이에 줄바꿈 추가

        // 동적 규칙이 없는 경우를 처리합니다.
        String rules = (c == null || c.interactionRules() == null || c.interactionRules().isEmpty())
                ? "(no dynamic rules provided)"
                : c.interactionRules().entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n"));

        return """
               ### CONTEXT
               %s

               ### DYNAMIC RELATIONSHIP RULES
               %s

               ### INSTRUCTIONS
               - Respect **DYNAMIC RELATIONSHIP RULES** when synthesizing your answer.
               - Do not infer unstated relationships or invent facts.
               - If the context is insufficient to form an answer, respond with "정보 없음".
               """.formatted(ctx, rules);
    }

    // ⛔️ 아래의 중복된 createPrompt(PromptContext c) 메서드는 삭제되었습니다.

    /**
     * [기존] 문서(Content) 목록을 번호가 매겨진 불릿 리스트 문자열로 변환합니다.
     */
    private String formatDocsAsBulletedList(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return "- (no context provided)\n";
        }

        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Content doc : docs) {
            // Content 객체가 TextSegment를 포함하는지 확인하고 텍스트를 추출합니다.
            String text = Optional.ofNullable(doc.textSegment())
                    .map(TextSegment::text)
                    .orElseGet(doc::toString); // textSegment가 없으면 toString() 결과를 사용합니다.

            if (text == null || text.isBlank()) continue;

            sb.append(String.format("- [%d] %s\n", ++i, trim(text, 700)));
        }
        return sb.toString();
    }

    /**
     * [기존] 문자열을 최대 길이에 맞춰 자르고 "/* ... *&#47;"를 붙입니다.
     */
    private String trim(String s, int maxLength) {
        if (s == null) return "";
        return s.length() > maxLength ? s.substring(0, maxLength) + "/* ... *&#47;" : s;
    }
}