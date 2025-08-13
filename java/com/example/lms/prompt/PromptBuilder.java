package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.List;               // 🔹 ← 누락
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
            if (!CollectionUtils.isEmpty(ctx.allowedElements())) {
                sys.append("- Allowed elements: ").append(String.join(",", ctx.allowedElements())).append("\n");
            }
            if (!CollectionUtils.isEmpty(ctx.discouragedElements())) {
                sys.append("- Discouraged elements: ").append(String.join(",", ctx.discouragedElements())).append("\n");
            }
            // RECOMMENDATION/PAIRING 공통 보수적 가드
            if ("RECOMMENDATION".equalsIgnoreCase(ctx.intent()) || "PAIRING".equalsIgnoreCase(ctx.intent())) {
                if (StringUtils.hasText(ctx.subjectName())) {
                    sys.append("- Recommend partners ONLY for subject: ").append(ctx.subjectName()).append("\n");
                }
                if (!CollectionUtils.isEmpty(ctx.trustedHosts())) {
                    sys.append("- Prefer trusted domains: ").append(String.join(",", ctx.trustedHosts())).append("\n");
                }
                sys.append("- Answer conservatively; prefer synergy evidence; if unsure, say '정보 없음'.\n");
            }
        }
        return sys.toString();
    }

    private static String join(List<Content> list) {
        return list.stream().map(Object::toString).collect(Collectors.joining("\n"));
    }
}
