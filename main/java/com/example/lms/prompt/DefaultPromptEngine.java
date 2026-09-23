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

@Component("defaultPromptEngine")
@Primary
public class DefaultPromptEngine implements PromptEngine {

    private static final String TEMPLATE = """
            ### CONTEXT
            %s

            ### QUESTION
            %s

            ### INSTRUCTIONS
            - Answer using the provided context.
            - Do not invent facts.
            - Answer in Korean unless the user asks otherwise.
            - If context is insufficient, say that the available evidence is insufficient.
            """;

    @Override
    public String createPrompt(String question, List<Content> docs) {
        return TEMPLATE.formatted(formatDocsAsBulletedList(docs), question == null ? "" : question);
    }

    @Override
    public String createPrompt(PromptContext context) {
        String docs = Stream.of(
                        context == null ? List.<Content>of() : context.web(),
                        context == null ? List.<Content>of() : context.rag())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(this::contentText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n"));

        String rules = context == null || context.interactionRules() == null || context.interactionRules().isEmpty()
                ? "(no dynamic rules provided)"
                : context.interactionRules().entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": " + String.join(", ", entry.getValue()))
                .collect(Collectors.joining("\n"));

        return """
                ### CONTEXT
                %s

                ### DYNAMIC RELATIONSHIP RULES
                %s

                ### INSTRUCTIONS
                - Respect dynamic relationship rules when synthesizing the answer.
                - Do not infer unstated relationships or invent facts.
                - If context is insufficient, say that the available evidence is insufficient.
                """.formatted(docs, rules);
    }

    private String formatDocsAsBulletedList(List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return "- (no context provided)\n";
        }

        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Content doc : docs) {
            String text = contentText(doc);
            if (text == null || text.isBlank()) {
                continue;
            }
            builder.append("- [")
                    .append(++index)
                    .append("] ")
                    .append(trim(text, 700))
                    .append('\n');
        }
        return builder.toString();
    }

    private String contentText(Content doc) {
        if (doc == null) {
            return "";
        }
        return Optional.ofNullable(doc.textSegment())
                .map(TextSegment::text)
                .orElseGet(doc::toString);
    }

    private static String trim(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
