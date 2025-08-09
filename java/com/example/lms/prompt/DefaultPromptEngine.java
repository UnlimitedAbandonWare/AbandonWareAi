package com.example.lms.prompt;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * PromptEngine 인터페이스의 기본 구현체.
 * 정교한 템플릿과 문서 포맷팅 기능을 제공하며, Spring에서 기본으로 주입됩니다.
 */
@Component
@Primary // 여러 PromptEngine 구현체 중 이 클래스를 기본으로 사용

public class DefaultPromptEngine implements PromptEngine {

    // 원본 질문(question)을 포함하도록 템플릿 개선
    private static final String TEMPLATE = """
            ### CONTEXT (ranked, most relevant first)
            %s

            ### QUESTION
            %s

            ### INSTRUCTIONS
            - You are a helpful AI assistant.
            - Based on the CONTEXT above, answer the QUESTION.
            - Earlier context items have higher authority.
            - Answer in Korean.
            - If you can find the source, cite its number like [1], [2].
            - If the context is insufficient to answer, respond with "정보 없음".
            """;

    @Override
    public String createPrompt(String question, List<Content> docs) {
        String context = formatDocsAsBulletedList(docs);
        // String.format을 사용하여 템플릿에 컨텍스트와 질문을 삽입
        return String.format(TEMPLATE, context, question);
    }

    /**
     * 문서(Content) 목록을 번호가 매겨진 불릿 리스트 문자열로 변환합니다.
     */
    private String formatDocsAsBulletedList(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return "- (no context provided)\n";
        }

        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Content doc : docs) {
// src/main/java/com/example/lms/prompt/DefaultPromptEngine.java

            String text = Optional.ofNullable(doc.textSegment())
                    .map(TextSegment::text)
                    .orElse(doc.toString());
            if (text == null || text.isBlank()) continue;

            sb.append(String.format("- [%d] %s\n", ++i, trim(text, 700)));
        }
        return sb.toString();
    }

    /**
     * 문자열을 최대 길이에 맞춰 자르고 "…"를 붙입니다.
     */
    private String trim(String s, int maxLength) {
        if (s == null) return "";
        return s.length() > maxLength ? s.substring(0, maxLength) + "…" : s;
    }
}