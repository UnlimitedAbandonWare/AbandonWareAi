package com.example.lms.prompt;
import java.util.Map;
import java.util.Set;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;


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
    private static final String HIS_PREFIX = """
            ### HISTORY
            %s
            """;

    /** 컨텍스트 본문(자료 영역) */
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
            if (StringUtils.hasText(ctx.history())) {
                sb.append(HIS_PREFIX.formatted(ctx.history()));
            }
        }
        return sb.toString();
    }

    /** 시스템 인스트럭션(정책/의도/제약 영역) */
    public String buildInstructions(PromptContext ctx) {
        StringBuilder sys = new StringBuilder();
        sys.append("### INSTRUCTIONS\n");
        sys.append("- Earlier sections have higher authority: VECTOR RAG > HISTORY > WEB SEARCH.\n");
        sys.append("- Ground every claim in the provided sections; if evidence is insufficient, reply \"정보 없음\".\n");
        sys.append("- Cite specific snippets or sources inline when possible.\n");

        if (ctx != null) {
            if (StringUtils.hasText(ctx.domain())) {
                sys.append("- Domain: ").append(ctx.domain()).append("\n");
            }
            if (ctx.interactionRules() != null && !ctx.interactionRules().isEmpty()) {
                sys.append("### DYNAMIC RELATIONSHIP RULES\n");
                ctx.interactionRules().forEach((k, v) -> {
                    if (v != null && !v.isEmpty()) {
                        sys.append("- ").append(k).append(": ")
                                .append(String.join(",", v))
                                .append('\n');
                    }
                });
            }
            // RECOMMENDATION/PAIRING 공통 보수적 가드

            sys.append("- Answer conservatively; prefer synergy evidence; if unsure, say '정보 없음'.\n");
        }

        return sys.toString();
    }

    private static String join(List<Content> list) {
        return list.stream()
                .map(c -> {
                    var seg = c.textSegment(); // 1.x API
                    return (seg != null && seg.text() != null && !seg.text().isBlank())
                            ? seg.text()
                            : c.toString();
                })
                .collect(Collectors.joining("\n"));
    }
}
