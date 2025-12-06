package com.example.lms.service;

import com.example.lms.prompt.PromptEngine;
import dev.langchain4j.rag.content.Content;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.List;



/**
 * @deprecated 새로운 PromptBuilder 기반 구조로 대체됨.
 *             PromptOrchestrator / PromptBuilder 사용 권장.
 */
@Component
//@Primary
@Deprecated
public class SimplePromptEngine implements PromptEngine {
    @Override
    public String createPrompt(String query, List<Content> topDocs) {
        StringBuilder sb = new StringBuilder();
        if (query != null && !query.isBlank()) {
            sb.append("User question:\n")
              .append(query.trim())
              .append("\n\n");
        }
        if (topDocs != null && !topDocs.isEmpty()) {
            sb.append("Retrieved evidence:\n");
            int idx = 1;
            for (Content c : topDocs) {
                sb.append("- [").append(idx++).append("] ");
                Object titleObj = c.metadata() != null ? c.metadata().getOrDefault("title", "") : "";
                Object urlObj = c.metadata() != null ? c.metadata().getOrDefault("url", "") : "";
                String title = String.valueOf(titleObj == null ? "" : titleObj);
                String url = String.valueOf(urlObj == null ? "" : urlObj);
                String snippet = c.textSegment() != null ? c.textSegment().text() : "";
                if (title != null && !title.isBlank()) {
                    sb.append(title).append(" ");
                }
                if (url != null && !url.isBlank()) {
                    sb.append("(").append(url).append(")");
                }
                if (snippet != null && !snippet.isBlank()) {
                    sb.append("\n  ").append(snippet);
                }
                sb.append("\n\n");
            }
        } else {
            sb.append("No external evidence was retrieved. Answer based on your own knowledge.");
        }
        return sb.toString();
    }
}
