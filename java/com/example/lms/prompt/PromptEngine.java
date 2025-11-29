package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import java.util.List;



public interface PromptEngine {

    // 최종 프롬프트 생성(핵심)
    String createPrompt(String query, List<Content> docs);

    // 레코드 기반 컨텍스트로부터 프롬프트 생성(안전한 기본 구현)
    default String createPrompt(PromptContext ctx) {
        List<Content> docs = (ctx == null)
                ? List.of()
                : ((ctx.rag() == null || ctx.rag().isEmpty()) ? ctx.web() : ctx.rag());
        return createPrompt("", docs);
    }

    // 필요시 지시문/본문을 따로 빌드하는 헬퍼도 여기에 선언 가능
}