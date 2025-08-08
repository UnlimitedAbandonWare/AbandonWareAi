package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.List;               // 🔹 ← 누락
import java.util.stream.Collectors;


@Component
public class PromptBuilder {
    private static final String WEB_PREFIX = """
            ### LIVE WEB RESULTS
            %s
            """;
    private static final String RAG_PREFIX = """
            ### VECTOR RAG
            %s
            """;
    private static final String MEM_PREFIX = """
            ### LONG-TERM MEMORY
            %s
            """;

    public String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            if (ctx.web() != null && !ctx.web().isEmpty()) {
                sb.append(WEB_PREFIX.formatted(join(ctx.web())));
            }
            if (ctx.rag() != null && !ctx.rag().isEmpty()) {
                sb.append(RAG_PREFIX.formatted(join(ctx.rag())));
            }
            if (ctx.memory() != null && !ctx.memory().isBlank()) {
                sb.append(MEM_PREFIX.formatted(ctx.memory()));
            }
        }
        return sb.toString();
    }
    private static String join(List<Content> list) {
        return list.stream().map(Object::toString).collect(Collectors.joining("\n"));
    }
}
