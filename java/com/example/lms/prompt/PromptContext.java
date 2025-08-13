package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import java.util.List;
import java.util.Set;
/** LLM 프롬프트에 주입될 구조화 컨텍스트 */
@Builder
public record PromptContext(
        List<Content> web,   // 전체 리스트 그대로 세팅
        List<Content> rag,
        String memory,
        // --- 추가: 도메인/의도/제약 ---
        String domain,                 // e.g., "GENSHIN"
        String intent,                 // e.g., "RECOMMENDATION"
        Set<String> allowedElements,   // ["CRYO","HYDRO"]
        Set<String> discouragedElements // ["PYRO","DENDRO"]
) {}