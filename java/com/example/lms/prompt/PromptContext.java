package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import java.util.List;
import java.util.Set;
/** LLM 프롬프트에 주입될 구조화 컨텍스트 */
@Builder
public record PromptContext(

        List<Content> web,      // LIVE WEB RESULTS
        List<Content> rag,      // VECTOR RAG
        String memory,          // LONG-TERM MEMORY
        String history,         // HISTORY (대화 맥락 요약)
        // --- 추가: 도메인/의도/제약 ---
        String domain,                 // e.g., "GENSHIN"
        String intent,                 // e.g., "RECOMMENDATION"
        Set<String> allowedElements,   // ["CRYO","HYDRO"]
        Set<String> discouragedElements // ["PYRO","DENDRO"]
) {}