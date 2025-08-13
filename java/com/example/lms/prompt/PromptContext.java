package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import lombok.Builder;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Builder
public record PromptContext(
        List<Content> web,
        List<Content> rag,
        String memory,
        String history,
        String domain,
        String intent,
        Map<String, Set<String>> interactionRules
) {}
