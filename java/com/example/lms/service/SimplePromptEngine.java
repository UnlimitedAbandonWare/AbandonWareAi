package com.example.lms.service;
import com.example.lms.prompt.PromptEngine;

import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.List;
import com.example.lms.prompt.PromptEngine;

@Component
//@Primary
public class SimplePromptEngine implements PromptEngine {
    @Override
    public String createPrompt(String query, List<Content> topDocs) {
        // 안전하게 toString()만 합칩니다 (버전별 Content API 차이 회피)
        String docs = (topDocs == null ? "" : topDocs.toString());
        return docs;
    }
}
