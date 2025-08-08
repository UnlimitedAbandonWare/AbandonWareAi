package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import java.util.List;

/** LLM 프롬프트에 주입될 구조화 컨텍스트 */
@Builder
public record PromptContext(
        List<Content> web,   // 전체 리스트 그대로 세팅
        List<Content> rag,
        String memory) {}