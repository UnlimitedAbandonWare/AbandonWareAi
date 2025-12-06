
package com.example.lms.prompt;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;
import java.util.stream.Collectors;




@Component
@ConditionalOnProperty(name="prompt.standard.enabled", havingValue="true", matchIfMissing = true)
public class StandardPromptBuilder implements PromptBuilder {

    @Override
    public String build(List<PromptContext> contexts, String question) {
        String sources = contexts.stream()
                .map(c -> String.format("- [%s] %s (rank:%d score:%.3f)\n%s", c.source, c.title, c.rank, c.score, c.snippet))
                .collect(Collectors.joining("\n\n"));
        return """
                QUESTION:
                %s

                SOURCES:
                %s

                INSTRUCTIONS:
                **1. CONTEXT PRIORITY**
                - You MUST ground all factual statements in the SOURCES above.
                - If your pre-trained knowledge conflicts with SOURCES, **prefer the SOURCES**.
                - Do NOT invent attributes (element, job, year, background, abilities) that are not clearly supported by the SOURCES.
                **2. HANDLING INSUFFICIENT INFORMATION**
                - If the SOURCES do not contain enough information to answer the question, reply exactly with: "정보 없음".
                - Do NOT say "I don't have official information" or "There is insufficient data" when SOURCES clearly provide the answer.
                **3. CITATION**
                - Always cite which source(s) you used inline (e.g., [S1], [S2]).
                - Prefer official/academic domains when multiple sources exist.
                **4. STYLE**
                - Be concise and direct.
                - When SOURCES contain conflicting information, present both views and indicate which is more authoritative or prevalent.
                """.formatted(question, sources);
    }
}