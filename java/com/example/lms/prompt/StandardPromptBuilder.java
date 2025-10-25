
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
        return "QUESTION:\n" + question + "\n\nSOURCES:\n" + sources + 
               "\n\nINSTRUCTIONS:\n- Cite sources inline.\n- Prefer official/academic domains.\n- Be concise.\n";
    }
}