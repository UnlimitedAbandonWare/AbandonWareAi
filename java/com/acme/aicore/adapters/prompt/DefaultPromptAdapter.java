package com.acme.aicore.adapters.prompt;

import com.acme.aicore.domain.model.*;
import com.acme.aicore.domain.ports.PromptPort;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;




/**
 * Default prompt builder that constructs a system message and evidence section
 * from the provided ranked documents.  The prompt reminds the model to
 * provide citations using the indicated numbering style.  Because the
 * simplified {@link SearchBundle.Doc} does not contain URLs or titles this
 * implementation substitutes the document ID for both.
 */
@Component
public class DefaultPromptAdapter implements PromptPort {
    @Override
    public Prompt buildPrompt(SessionContext ctx, List<RankedDoc> docs, PromptParams params) {
        String sources = docs.stream().limit(params.maxCtx())
                .map(d -> {
                    // In a full implementation a ranked doc would carry a title,
                    // URL and snippet.  Here we reuse the ID as a shim.
                    String title = d.id();
                    String url = "#" + d.id();
                    String snippet = "";
                    return "- [" + title + "](" + url + ")\n  " + snippet;
                })
                .collect(Collectors.joining("\n"));
        String system = "당신은 근거를 명확히 제시하는 도우미입니다.\n" +
                "아래 컨텍스트는 검색을 통해 수집되었으며 부정확할 수 있습니다.\n" +
                "인용 시 [번호]와 출처를 병기하고, 모르면 모른다고 답하세요.";
        String userQuery = ctx.lastUserQuery() != null ? ctx.lastUserQuery().text() : "";
        String context = sources.isEmpty() ? "" : "사용 가능한 근거:\n" + sources;
        return new Prompt(system, userQuery, context);
    }
}